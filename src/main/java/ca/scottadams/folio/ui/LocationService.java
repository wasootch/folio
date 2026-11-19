package ca.scottadams.folio.ui;

import ca.scottadams.folio.logging.AppLogging;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * Lists what lives inside a {@link Location}. Every method here can block on
 * disk or network I/O, so call them from a background thread, never the EDT.
 */
final class LocationService {

    private static final Logger LOG = Logger.getLogger(AppLogging.LOGGER_NAME);

    /** Listing one server's shares is quick when the server is up. */
    private static final int NET_VIEW_SERVER_TIMEOUT_SECONDS = 15;
    private static final String SERVERS_KEY = "servers";

    private static final int DRIVE_REMOVABLE = 2;
    private static final int DRIVE_REMOTE = 4;
    private static final int DRIVE_CDROM = 5;

    private static final Preferences PREFS = Preferences.userRoot().node("ca/scottadams/folio");
    private static final String SHARES_KEY = "shares";

    private LocationService() {
    }

    /** The contents of a real folder: its subfolders and the EPUB files to show. */
    record Listing(List<Path> folders, List<Path> epubs) {
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    // ---------------------------------------------------------------- listing

    /** The child locations of {@code location} (used by the breadcrumb dropdowns and the home views). */
    static List<Location> listChildren(Location location) throws IOException {
        return switch (location.kind()) {
            case HOME -> List.of(Location.THIS_PC, Location.SHARES, Location.NETWORK);
            case THIS_PC -> drives();
            case SHARES -> savedShares();
            case NETWORK -> servers();
            case SERVER -> sharesOnServer(location.server());
            case FOLDER -> listFolder(location.path(), false).folders().stream()
                    .map(Location::folder)
                    .toList();
        };
    }

    /**
     * Lists a folder in one pass. With {@code recursive}, EPUBs from all
     * subfolders are included, but {@code folders} still holds only the direct children.
     */
    static Listing listFolder(Path folder, boolean recursive) throws IOException {
        if (!Files.isDirectory(folder)) {
            throw new IOException("Not a directory, or not reachable: " + folder);
        }

        List<Path> folders = new ArrayList<>();
        List<Path> epubs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path entry : stream) {
                BasicFileAttributes attrs = readAttributes(entry);
                if (attrs == null) {
                    continue;
                }
                if (attrs.isDirectory()) {
                    if (!isHiddenOrSystem(entry, attrs)) {
                        folders.add(entry);
                    }
                } else if (!recursive && attrs.isRegularFile() && isEpub(entry)) {
                    epubs.add(entry);
                }
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }

        if (recursive) {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && isEpub(file)) {
                        epubs.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    LOG.log(Level.FINE, "Skipping unreadable path " + file, exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        folders.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        Collections.sort(epubs);
        return new Listing(folders, epubs);
    }

    private static boolean isEpub(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".epub");
    }

    /** Reads attributes in a single call (DOS attributes on Windows); null if unreadable. */
    private static BasicFileAttributes readAttributes(Path path) {
        try {
            try {
                return Files.readAttributes(path, DosFileAttributes.class);
            } catch (UnsupportedOperationException e) {
                return Files.readAttributes(path, BasicFileAttributes.class);
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Skipping unreadable entry " + path, e);
            return null;
        }
    }

    private static boolean isHiddenOrSystem(Path path, BasicFileAttributes attrs) {
        if (attrs instanceof DosFileAttributes dos) {
            return dos.isHidden() || dos.isSystem();
        }
        return path.getFileName().toString().startsWith(".");
    }

    // ----------------------------------------------------------------- drives

    private static List<Location> drives() {
        List<Location> result = new ArrayList<>();
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            result.add(Location.folder(root, root + driveSuffix(root)));
        }
        return result;
    }

    /** A short " (network)" style hint for the drive type. GetDriveType never touches the network. */
    private static String driveSuffix(Path root) {
        if (!isWindows()) {
            return "";
        }
        try {
            int type = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetDriveType(root.toString());
            return switch (type) {
                case DRIVE_REMOTE -> "  (network drive)";
                case DRIVE_REMOVABLE -> "  (removable)";
                case DRIVE_CDROM -> "  (optical)";
                default -> "";
            };
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- network

    private static List<Location> savedShares() {
        List<Location> result = new ArrayList<>();
        for (String unc : savedShareNames()) {
            try {
                result.add(Location.folder(Path.of(unc), unc));
            } catch (InvalidPathException e) {
                LOG.log(Level.WARNING, "Ignoring invalid saved share " + unc, e);
            }
        }
        return result;
    }

    /**
     * Servers we know about from saved shares, plus whatever background discovery has found so far.
     * Never waits: this also (idempotently) makes sure a discovery is running or recent.
     */
    private static List<Location> servers() {
        Map<String, String> servers = new LinkedHashMap<>();
        for (String server : readList(SERVERS_KEY)) {
            servers.putIfAbsent(server.toLowerCase(Locale.ROOT), server);
        }
        for (String unc : savedShareNames()) {
            String server = unc.substring(2).split("\\\\", 2)[0];
            servers.putIfAbsent(server.toLowerCase(Locale.ROOT), server);
        }
        NetworkDiscovery discovery = NetworkDiscovery.instance();
        discovery.start(false);
        for (String server : discovery.servers()) {
            servers.putIfAbsent(server.toLowerCase(Locale.ROOT), server);
        }
        return servers.values().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .map(Location::server)
                .toList();
    }

    private static List<Location> sharesOnServer(String server) throws IOException {
        Map<String, Location> shares = new LinkedHashMap<>();
        IOException failure = null;
        try {
            for (String name : parseShares(runNetView(server, NET_VIEW_SERVER_TIMEOUT_SECONDS))) {
                String unc = "\\\\" + server + "\\" + name;
                shares.put(unc.toLowerCase(Locale.ROOT), Location.folder(Path.of(unc), name));
            }
            addToList(SERVERS_KEY, server); // it answered, so list it under "Network" from now on
        } catch (IOException e) {
            failure = e;
        }

        String prefix = ("\\\\" + server + "\\").toLowerCase(Locale.ROOT);
        for (String unc : savedShareNames()) {
            if (unc.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                shares.putIfAbsent(unc.toLowerCase(Locale.ROOT),
                        Location.folder(Path.of(unc), unc.substring(prefix.length())));
            }
        }

        if (shares.isEmpty() && failure != null) {
            throw failure;
        }
        List<Location> result = new ArrayList<>(shares.values());
        result.sort(Comparator.comparing(Location::displayName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    static String runNetView(String server, int timeoutSeconds) throws IOException {
        if (!isWindows()) {
            throw new IOException("Network browsing is only available on Windows.");
        }
        List<String> command = new ArrayList<>(List.of("net", "view"));
        if (server != null) {
            command.add("\\\\" + server);
        }
        LOG.fine(() -> "Running: " + String.join(" ", command));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try {
                return process.getInputStream().readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        byte[] bytes;
        try {
            bytes = output.get(timeoutSeconds, TimeUnit.SECONDS);
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            process.destroyForcibly();
            throw new IOException("Timed out browsing the network"
                    + (server != null ? " for \\\\" + server : "") + ".");
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while browsing the network.");
        } catch (ExecutionException e) {
            process.destroyForcibly();
            throw new IOException("Could not run 'net view': " + e.getCause().getMessage(), e.getCause());
        }

        String text = new String(bytes, consoleCharset());
        if (process.isAlive() || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IOException(firstMessageLine(text));
        }
        return text;
    }

    private static Charset consoleCharset() {
        for (String property : new String[]{"sun.stdout.encoding", "native.encoding"}) {
            String name = System.getProperty(property);
            if (name != null) {
                try {
                    return Charset.forName(name);
                } catch (RuntimeException e) {
                    // try the next one
                }
            }
        }
        return Charset.defaultCharset();
    }

    private static String firstMessageLine(String text) {
        for (String line : text.split("\\R")) {
            if (!line.isBlank() && !line.startsWith("More help")) {
                return line.trim();
            }
        }
        return "Network browsing failed.";
    }

    /** Parses {@code net view}: lines such as {@code \\SERVER   comment}. */
    static List<String> parseServers(String text) {
        List<String> servers = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.startsWith("\\\\")) {
                servers.add(line.substring(2).trim().split("\\s+")[0]);
            }
        }
        return servers;
    }

    /** Parses {@code net view \\server}: a table of share name / type / comment below a dashed rule. */
    static List<String> parseShares(String text) {
        List<String> shares = new ArrayList<>();
        boolean inTable = false;
        for (String line : text.split("\\R")) {
            if (line.startsWith("---")) {
                inTable = true;
                continue;
            }
            if (!inTable || line.isBlank()) {
                continue;
            }
            String[] columns = line.trim().split("\\s{2,}");
            if (columns.length < 2) {
                continue; // e.g. "The command completed successfully."
            }
            String name = columns[0];
            if (name.endsWith("$") || columns[1].equalsIgnoreCase("Print")) {
                continue; // administrative shares and printers
            }
            shares.add(name);
        }
        return shares;
    }

    // ------------------------------------------------------------ saved shares

    /** Remembers the share a successfully opened folder lives on, so it appears under "Network shares". */
    static void rememberShareOf(Path folder) {
        if (!Location.isUnc(folder)) {
            return;
        }
        String[] parts = Location.uncParts(folder.getRoot());
        if (parts[1].isEmpty()) {
            return;
        }
        addToList(SHARES_KEY, "\\\\" + parts[0] + "\\" + parts[1]);
    }

    static void forgetShare(String unc) {
        synchronized (PREFS) {
            List<String> shares = readList(SHARES_KEY);
            if (shares.removeIf(unc::equalsIgnoreCase)) {
                PREFS.put(SHARES_KEY, String.join("\n", shares));
            }
        }
    }

    private static List<String> savedShareNames() {
        return readList(SHARES_KEY);
    }

    private static void addToList(String key, String value) {
        synchronized (PREFS) {
            List<String> values = readList(key);
            if (values.stream().noneMatch(value::equalsIgnoreCase)) {
                values.add(value);
                PREFS.put(key, String.join("\n", values));
            }
        }
    }

    /** A newline-separated list stored in preferences, sorted case-insensitively. */
    private static List<String> readList(String key) {
        List<String> values = new ArrayList<>();
        synchronized (PREFS) {
            for (String line : PREFS.get(key, "").split("\n")) {
                if (!line.isBlank()) {
                    values.add(line.trim());
                }
            }
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }
}

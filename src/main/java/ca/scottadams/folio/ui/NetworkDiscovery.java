package ca.scottadams.folio.ui;

import ca.scottadams.folio.logging.AppLogging;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Finds the computers and devices on the local network, the way Explorer's "Network" folder does.
 * <p>
 * Discovery can take many seconds, so it runs once, in one background thread, and keeps going when the
 * user navigates elsewhere. {@link #start} is idempotent: calling it while a search is running does
 * nothing, and a finished search is reused for {@value #REFRESH_AFTER_SECONDS} seconds before another is
 * allowed. Results accumulate and listeners are told as each server turns up.
 * <p>
 * Two sources are used, in order: the Windows shell's Network folder (the same source as Explorer,
 * which sees devices found through WS-Discovery and not just the legacy NetBIOS browser), then
 * {@code net view} as a fallback.
 */
final class NetworkDiscovery {

    private static final Logger LOG = Logger.getLogger(AppLogging.LOGGER_NAME);

    private static final NetworkDiscovery INSTANCE = new NetworkDiscovery();

    static final int REFRESH_AFTER_SECONDS = 300;
    private static final int SHELL_TIMEOUT_SECONDS = 60;
    private static final int NET_VIEW_TIMEOUT_SECONDS = 15;

    /** Enumerates Explorer's Network folder (shell folder 18) and prints each item's UNC path. */
    private static final String SHELL_SCRIPT =
            "$ErrorActionPreference='SilentlyContinue';"
                    + "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
                    + "$s=New-Object -ComObject Shell.Application;"
                    + "foreach($i in $s.Namespace(18).Items()){"
                    + "$p=[string]$i.Path;"
                    + "if($p.StartsWith('\\\\')){[Console]::Out.WriteLine($p);[Console]::Out.Flush()}}";

    static NetworkDiscovery instance() {
        return INSTANCE;
    }

    /** Lower-case name to display name; guarded by {@code this}. */
    private final Map<String, String> found = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private boolean running;
    /** When the last search ended (nanoTime), or null if none has finished yet; guarded by {@code this}. */
    private Long finishedAt;
    private volatile Process process;

    private NetworkDiscovery() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process p = process;
            if (p != null) {
                p.destroyForcibly();
            }
        }));
    }

    /**
     * Starts a search unless one is already running or a recent one finished.
     *
     * @param force ignore how recent the last search was (still never runs two at once)
     * @return true if a new search was started
     */
    synchronized boolean start(boolean force) {
        if (!LocationService.isWindows() || running) {
            return false;
        }
        if (!force && finishedAt != null
                && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - finishedAt) < REFRESH_AFTER_SECONDS) {
            return false;
        }
        running = true;
        Thread thread = new Thread(this::search, "folio-network-discovery");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    synchronized boolean isRunning() {
        return running;
    }

    /** Everything found so far, across searches. */
    synchronized List<String> servers() {
        return new ArrayList<>(found.values());
    }

    /** Called from the discovery thread when a server is found and when a search ends. */
    void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private void search() {
        LOG.info("Searching the network for servers …");
        long started = System.nanoTime();
        try {
            try {
                searchShell();
            } catch (IOException e) {
                LOG.log(Level.INFO, "Shell network discovery unavailable: " + e.getMessage());
            }
            try {
                for (String server : LocationService.parseServers(LocationService.runNetView(null, NET_VIEW_TIMEOUT_SECONDS))) {
                    add(server);
                }
            } catch (IOException e) {
                LOG.log(Level.FINE, "'net view' found nothing: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Network discovery failed", e);
        } finally {
            synchronized (this) {
                running = false;
                finishedAt = System.nanoTime();
            }
            LOG.info(() -> "Network search finished in " + (System.nanoTime() - started) / 1_000_000
                    + " ms; " + servers().size() + " server(s) known.");
            fire();
        }
    }

    private void searchShell() throws IOException {
        String encoded = Base64.getEncoder().encodeToString(SHELL_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        process = p;
        // If the shell hangs, killing the process ends the read loop below.
        CompletableFuture.runAsync(p::destroyForcibly,
                CompletableFuture.delayedExecutor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String name = line.trim();
                while (name.startsWith("\\")) {
                    name = name.substring(1);
                }
                if (!name.isEmpty() && !name.contains("\\") && !name.contains("/") && !name.contains(":")) {
                    add(name);
                }
            }
        } finally {
            p.destroyForcibly();
            process = null;
        }
    }

    private void add(String name) {
        boolean added;
        synchronized (this) {
            added = found.putIfAbsent(name.toLowerCase(Locale.ROOT), name) == null;
        }
        if (added) {
            LOG.fine(() -> "Discovered " + name);
            fire();
        }
    }

    private void fire() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Discovery listener failed", e);
            }
        }
    }
}

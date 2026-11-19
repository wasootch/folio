package ca.scottadams.folio.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A place the user can navigate to. Besides real folders there are a few
 * virtual locations that form the top of the hierarchy:
 *
 * <pre>
 * Locations
 *   ├─ This computer      → drives (physical and mapped) → folders…
 *   ├─ Network shares     → shares the user has opened before
 *   └─ Network            → servers → shares → folders…
 * </pre>
 *
 * @param kind   what sort of location this is
 * @param path   the folder, for {@link Kind#FOLDER}
 * @param server the host name, for {@link Kind#SERVER}
 * @param label  display text overriding the default (e.g. a drive or share name)
 */
record Location(Kind kind, Path path, String server, String label) {

    enum Kind { HOME, THIS_PC, SHARES, NETWORK, SERVER, FOLDER }

    static final Location HOME = new Location(Kind.HOME, null, null, null);
    static final Location THIS_PC = new Location(Kind.THIS_PC, null, null, null);
    static final Location SHARES = new Location(Kind.SHARES, null, null, null);
    static final Location NETWORK = new Location(Kind.NETWORK, null, null, null);

    static Location folder(Path path) {
        return new Location(Kind.FOLDER, path, null, null);
    }

    static Location folder(Path path, String label) {
        return new Location(Kind.FOLDER, path, null, label);
    }

    static Location server(String server) {
        return new Location(Kind.SERVER, null, server, null);
    }

    String displayName() {
        if (label != null) {
            return label;
        }
        return switch (kind) {
            case HOME -> "Locations";
            case THIS_PC -> "This computer";
            case SHARES -> "Network shares";
            case NETWORK -> "Network";
            case SERVER -> server;
            case FOLDER -> {
                Path name = path.getFileName();
                yield name != null ? name.toString() : path.toString();
            }
        };
    }

    /** The text shown when the user clicks into the address bar to type a path. */
    String editText() {
        return switch (kind) {
            case FOLDER -> path.toString();
            case SERVER -> "\\\\" + server;
            default -> "";
        };
    }

    /** The location one level up, or {@code null} at the top. */
    Location parent() {
        List<Location> trail = trail();
        return trail.size() < 2 ? null : trail.get(trail.size() - 2);
    }

    /** The chain of locations from the top down to this one, inclusive (the breadcrumbs). */
    List<Location> trail() {
        List<Location> trail = new ArrayList<>();
        trail.add(HOME);
        switch (kind) {
            case HOME -> {
            }
            case THIS_PC -> trail.add(THIS_PC);
            case SHARES -> trail.add(SHARES);
            case NETWORK -> trail.add(NETWORK);
            case SERVER -> {
                trail.add(NETWORK);
                trail.add(this);
            }
            case FOLDER -> addFolderTrail(trail);
        }
        return trail;
    }

    private void addFolderTrail(List<Location> trail) {
        Path absolute = path.toAbsolutePath().normalize();
        Path root = absolute.getRoot();
        if (root == null) {
            trail.add(THIS_PC);
            trail.add(folder(absolute));
            return;
        }

        if (isUnc(absolute)) {
            String[] parts = uncParts(root);
            trail.add(NETWORK);
            trail.add(server(parts[0]));
            trail.add(folder(root, parts[1]));
        } else {
            trail.add(THIS_PC);
            trail.add(folder(root, root.toString()));
        }

        Path current = root;
        for (Path name : root.relativize(absolute)) {
            if (name.toString().isEmpty()) {
                continue;
            }
            current = current.resolve(name);
            trail.add(folder(current));
        }
    }

    /** True for a Windows UNC path such as {@code \\server\share\folder}. */
    static boolean isUnc(Path path) {
        Path root = path.getRoot();
        return root != null && root.toString().startsWith("\\\\");
    }

    /** Splits a UNC root ({@code \\server\share\}) into {@code {server, share}}. */
    static String[] uncParts(Path uncRoot) {
        String text = uncRoot.toString().substring(2);
        while (text.endsWith("\\")) {
            text = text.substring(0, text.length() - 1);
        }
        String[] parts = text.split("\\\\", 2);
        return new String[]{parts[0], parts.length > 1 ? parts[1] : ""};
    }
}

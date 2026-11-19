package ca.scottadams.folio.rename;

/**
 * Makes an arbitrary string safe to use as a single path segment on Windows,
 * macOS, and Linux file systems.
 */
public final class FilenameSanitizer {

    private static final int MAX_LENGTH = 180;

    private FilenameSanitizer() {
    }

    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }

        StringBuilder result = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || "<>:\"/\\|?*".indexOf(c) >= 0) {
                result.append(' ');
            } else {
                result.append(c);
            }
        }

        String collapsed = result.toString().trim().replaceAll("\\s+", " ");

        // Windows disallows trailing dots/spaces on path segments.
        while (collapsed.endsWith(".") || collapsed.endsWith(" ")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }

        if (collapsed.length() > MAX_LENGTH) {
            collapsed = collapsed.substring(0, MAX_LENGTH).trim();
        }

        return collapsed.isEmpty() ? "untitled" : collapsed;
    }
}

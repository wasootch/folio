package ca.scottadams.folio;

/**
 * Command-line options.
 *
 * @param debug     show the log panel at startup ({@code --debug})
 * @param startPath open this path at startup, as if it had been typed into the address bar
 *                  ({@code --path <path>} or {@code --path=<path>}); null if not given
 * @param help      print usage and exit ({@code --help})
 */
public record AppOptions(boolean debug, String startPath, boolean help) {

    public static final String USAGE = """
            Usage: java -jar folio.jar [options]
              --debug          show the log panel at startup (also under Help > Show Log Panel)
              --path <path>    start at this folder, e.g. --path "\\\\SERVER_NAME\\Books\\Some Author"
              --help           show this message
            """;

    /**
     * Parses {@code args}; unrecognised arguments are reported through {@code onUnknown}.
     * <p>
     * On Windows a path ending in a backslash inside quotes, e.g. {@code --path "\\server\share\\"},
     * is mangled by the command-line parser: the {@code \"} becomes a literal quote and the rest of the
     * command line is swallowed into the same argument. That is repaired here by cutting the path at the
     * stray quote and parsing whatever followed as further arguments.
     */
    public static AppOptions parse(String[] args, java.util.function.Consumer<String> onUnknown) {
        boolean debug = false;
        boolean help = false;
        String path = null;
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(java.util.List.of(args));
        while (!queue.isEmpty()) {
            String arg = queue.poll();
            String value = null;
            if (arg.equals("--debug")) {
                debug = true;
            } else if (arg.equals("--help") || arg.equals("-h")) {
                help = true;
            } else if (arg.equals("--path") && !queue.isEmpty()) {
                value = queue.poll();
            } else if (arg.startsWith("--path=")) {
                value = arg.substring("--path=".length());
            } else {
                onUnknown.accept(arg);
            }
            if (value != null) {
                int quote = value.indexOf('"');
                if (quote >= 0) {
                    String rest = value.substring(quote + 1).trim();
                    value = value.substring(0, quote);
                    if (!rest.isEmpty()) {
                        String[] tokens = rest.split("\s+");
                        for (int i = tokens.length - 1; i >= 0; i--) {
                            queue.addFirst(tokens[i]);
                        }
                    }
                }
                path = value;
            }
        }
        return new AppOptions(debug, path, help);
    }
}

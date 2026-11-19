package ca.scottadams.folio.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Configures {@code java.util.logging} for the whole app: a console handler
 * (visible when launched from a terminal) and a log file under the user's
 * home directory, appended to across runs (visible even when launched by
 * double-clicking the jar, where there is no console to see).
 */
public final class AppLogging {

    public static final String LOGGER_NAME = "ca.scottadams.folio";

    private AppLogging() {
    }

    public static Path configure() {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT [%4$s] %3$s - %5$s%6$s%n");

        Logger root = Logger.getLogger(LOGGER_NAME);
        root.setLevel(Level.FINE);
        root.setUseParentHandlers(false);

        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(Level.INFO);
        console.setFormatter(new SimpleFormatter());
        root.addHandler(console);

        Path logFile = logFilePath();
        try {
            Files.createDirectories(logFile.getParent());
            // The 2-arg (pattern, append) constructor writes exactly this filename with no
            // generation suffix; the (pattern, limit, count, append) form appends ".0" etc.
            // even when the pattern has no "%g", which would silently break logFilePath().
            FileHandler fileHandler = new FileHandler(logFile.toString(), true);
            fileHandler.setLevel(Level.FINE);
            fileHandler.setFormatter(new SimpleFormatter());
            root.addHandler(fileHandler);
        } catch (IOException e) {
            root.log(Level.WARNING, "Could not open log file " + logFile + "; continuing without it.", e);
        }

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                root.log(Level.SEVERE, "Uncaught exception on thread " + thread.getName(), throwable));

        return logFile;
    }

    public static Path logFilePath() {
        return Path.of(System.getProperty("user.home"), ".folio", "folio.log");
    }
}

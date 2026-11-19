package ca.scottadams.folio;

import ca.scottadams.folio.logging.AppLogging;
import ca.scottadams.folio.ui.MainWindow;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point for Folio, a desktop tool for browsing a folder of EPUB files,
 * inspecting their metadata, and bulk-renaming them from a template.
 */
public final class FolioApp {

    public static void main(String[] args) {
        AppOptions options = AppOptions.parse(args, arg -> System.err.println("Ignoring unknown argument: " + arg
                + (arg.equals("--path") ? " (needs a value)" : "")));
        if (options.help()) {
            System.out.print(AppOptions.USAGE);
            return;
        }
        Path logFile = AppLogging.configure();
        Logger log = Logger.getLogger(AppLogging.LOGGER_NAME);
        log.log(Level.INFO, "Folio starting; log file at {0}", logFile);

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            log.log(Level.WARNING, "Could not set system look and feel; using default.", e);
        }

        SwingUtilities.invokeLater(() -> new MainWindow(options).setVisible(true));
    }
}

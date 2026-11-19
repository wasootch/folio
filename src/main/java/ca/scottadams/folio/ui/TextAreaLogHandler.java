package ca.scottadams.folio.ui;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * A {@code java.util.logging.Handler} that appends formatted log records to a
 * Swing text area, so the app's own window can show what it's doing (and why
 * something failed) without needing a console or a text editor.
 */
final class TextAreaLogHandler extends Handler {

    private final JTextArea textArea;

    TextAreaLogHandler(JTextArea textArea) {
        this.textArea = textArea;
        setFormatter(new SimpleFormatter());
    }

    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }
        String message = getFormatter().format(record);
        SwingUtilities.invokeLater(() -> {
            textArea.append(message);
            textArea.setCaretPosition(textArea.getDocument().getLength());
        });
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}

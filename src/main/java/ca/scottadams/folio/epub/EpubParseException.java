package ca.scottadams.folio.epub;

import java.io.IOException;

/**
 * Raised when an EPUB file cannot be opened or its metadata cannot be located.
 */
public class EpubParseException extends IOException {

    public EpubParseException(String message) {
        super(message);
    }

    public EpubParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

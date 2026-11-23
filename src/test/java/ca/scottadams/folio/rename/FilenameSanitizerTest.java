package ca.scottadams.folio.rename;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilenameSanitizerTest {

    @Test
    void replacesReservedCharactersAndCollapsesSpaces() {
        assertEquals("a b c d e f g h i", FilenameSanitizer.sanitize("a<b>c:d\"e/f\\g|h?i"));
        assertEquals("one two", FilenameSanitizer.sanitize("  one \t\n  two  "));
    }

    @Test
    void stripsControlCharacters() {
        assertEquals("a b", FilenameSanitizer.sanitize("a\u0001\u001fb"));
    }

    @Test
    void removesTrailingDotsAndSpacesWhichWindowsForbids() {
        assertEquals("Title", FilenameSanitizer.sanitize("Title. . ."));
    }

    @Test
    void emptyOrNullBecomesUntitled() {
        assertEquals("untitled", FilenameSanitizer.sanitize(""));
        assertEquals("untitled", FilenameSanitizer.sanitize("   "));
        assertEquals("untitled", FilenameSanitizer.sanitize("..."));
        assertEquals("", FilenameSanitizer.sanitize(null));
    }

    @Test
    void longNamesAreTruncatedAndNotLeftEndingInASpace() {
        String name = FilenameSanitizer.sanitize(("word ").repeat(100));

        assertTrue(name.length() <= 180, "length " + name.length());
        assertTrue(!name.endsWith(" "));
    }

    @Test
    void keepsUnicodeAndOrdinaryPunctuation() {
        assertEquals("Les Misérables, Vol. 1 - 日本語 (2nd ed) [x]",
                FilenameSanitizer.sanitize("Les Misérables, Vol. 1 - 日本語 (2nd ed) [x]"));
    }
}

package ca.scottadams.folio.rename;

import ca.scottadams.folio.model.BookMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenameTemplateTest {

    private static BookMetadata book(String title, List<String> authors, String series, Double index) {
        return new BookMetadata(title, authors, series, index, "Tor", "2010-08-31", "en", "id", null);
    }

    private static final BookMetadata STORMLIGHT =
            book("The Way of Kings", List.of("Brandon Sanderson", "Co Author"), "Stormlight Archive", 1.0);

    @Test
    void defaultTemplateWithSeries() {
        assertEquals("Brandon Sanderson - Stormlight Archive 01 - The Way of Kings",
                RenameTemplate.render(RenameTemplate.DEFAULT_TEMPLATE, STORMLIGHT));
    }

    @Test
    void emptySeriesDoesNotLeaveDanglingSeparators() {
        BookMetadata standalone = book("Elantris", List.of("Brandon Sanderson"), null, null);

        assertEquals("Brandon Sanderson - Elantris", RenameTemplate.render(RenameTemplate.DEFAULT_TEMPLATE, standalone));
    }

    @Test
    void blankTemplateFallsBackToASensibleDefault() {
        BookMetadata standalone = book("Elantris", List.of("Brandon Sanderson"), null, null);

        assertEquals("Brandon Sanderson - Elantris", RenameTemplate.render("  ", standalone));
        assertEquals("Brandon Sanderson - Stormlight Archive 01 - The Way of Kings",
                RenameTemplate.render(null, STORMLIGHT));
    }

    @Test
    void authorIsTheFirstAuthorAndAuthorsJoinsAll() {
        assertEquals("Brandon Sanderson", RenameTemplate.render("{author}", STORMLIGHT));
        assertEquals("Brandon Sanderson, Co Author", RenameTemplate.render("{authors}", STORMLIGHT));
    }

    @Test
    void yearComesFromThePublishedDate() {
        assertEquals("2010", RenameTemplate.render("{year}", STORMLIGHT));
        BookMetadata noDate = new BookMetadata("T", List.of(), null, null, null, "20", null, null, null);
        assertEquals("T", RenameTemplate.render("{title} {year}", noDate));
    }

    @ParameterizedTest
    @CsvSource({"1.0,01", "7.0,07", "12.0,12", "1.5,01.5", "10.5,10.5"})
    void seriesIndexIsZeroPadded(double index, String expected) {
        BookMetadata m = book("T", List.of(), "S", index);

        assertEquals(expected, RenameTemplate.render("{series_index}", m));
    }

    @Test
    void tokensAreCaseInsensitiveAndUnknownOnesRenderEmpty() {
        assertEquals("The Way of Kings", RenameTemplate.render("{TITLE}", STORMLIGHT));
        assertEquals("The Way of Kings", RenameTemplate.render("{title}{nonsense}", STORMLIGHT));
    }

    @Test
    void textOutsideTokensIsKept() {
        assertEquals("[Tor] The Way of Kings (en)", RenameTemplate.render("[{publisher}] {title} ({language})", STORMLIGHT));
    }

    @Test
    void replacementTextWithDollarsAndBackslashesIsLiteral() {
        BookMetadata m = book("Cost $5 \\ more $1", List.of("A"), null, null);

        assertEquals("Cost $5 more $1", RenameTemplate.render("{title}", m));
    }

    @Test
    void illegalFilenameCharactersAreRemoved() {
        BookMetadata m = book("What? Who: \"Me\" <or> You/Them|*", List.of("A"), null, null);

        String name = RenameTemplate.render("{title}", m);

        assertEquals("What Who Me or You Them", name.replaceAll("\\s+", " ").trim().replaceAll(" +$", ""));
        for (char c : "<>:\"/\\|?*".toCharArray()) {
            assertTrue(name.indexOf(c) < 0, "contains " + c + ": " + name);
        }
    }

    @Test
    void everythingEmptyBecomesUntitled() {
        assertEquals("untitled", RenameTemplate.render("{series}", BookMetadata.empty()));
    }
}

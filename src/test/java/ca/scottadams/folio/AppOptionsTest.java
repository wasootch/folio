package ca.scottadams.folio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppOptionsTest {

    private final List<String> unknown = new ArrayList<>();

    private AppOptions parse(String... args) {
        unknown.clear();
        return AppOptions.parse(args, unknown::add);
    }

    @Test
    void noArgumentsMeansDefaults() {
        AppOptions o = parse();

        assertFalse(o.debug());
        assertFalse(o.help());
        assertNull(o.startPath());
        assertTrue(unknown.isEmpty());
    }

    @Test
    void parsesFlags() {
        assertTrue(parse("--debug").debug());
        assertTrue(parse("--help").help());
        assertTrue(parse("-h").help());
    }

    @Test
    void parsesPathInBothForms() {
        assertEquals("C:\\Books", parse("--path", "C:\\Books").startPath());
        assertEquals("C:\\Books", parse("--path=C:\\Books").startPath());
        assertEquals("\\\\SERVER_NAME\\Books\\Some Author", parse("--path", "\\\\SERVER_NAME\\Books\\Some Author").startPath());
    }

    @Test
    void pathWithoutAValueIsReportedAsUnknown() {
        AppOptions o = parse("--path");

        assertNull(o.startPath());
        assertEquals(List.of("--path"), unknown);
    }

    @Test
    void unknownArgumentsAreReportedAndIgnored() {
        AppOptions o = parse("--bogus", "--debug", "stray");

        assertTrue(o.debug());
        assertEquals(List.of("--bogus", "stray"), unknown);
    }

    @Test
    void optionsCanComeInAnyOrder() {
        AppOptions o = parse("--path", "D:\\x", "--debug");

        assertTrue(o.debug());
        assertEquals("D:\\x", o.startPath());
    }

    /**
     * Windows turns {@code --path "\\SERVER_NAME\Books\" --debug} into one argument,
     * {@code \\SERVER_NAME\Books" --debug}, because {@code \"} is an escaped quote.
     */
    @Test
    void repairsATrailingBackslashBeforeTheClosingQuote() {
        AppOptions o = parse("--path", "\\\\SERVER_NAME\\Books\" --debug");

        assertEquals("\\\\SERVER_NAME\\Books", o.startPath());
        assertTrue(o.debug());
        assertTrue(unknown.isEmpty());
    }

    @Test
    void repairHandlesSeveralSwallowedArguments() {
        AppOptions o = parse("--path", "C:\\Books\" --debug\t--help");

        assertEquals("C:\\Books", o.startPath());
        assertTrue(o.debug());
        assertTrue(o.help());
    }

    @Test
    void repairWorksWithTheEqualsForm() {
        AppOptions o = parse("--path=C:\\Books\" --debug");

        assertEquals("C:\\Books", o.startPath());
        assertTrue(o.debug());
    }

    @Test
    void aQuoteWithNothingAfterItJustEndsThePath() {
        AppOptions o = parse("--path", "C:\\Books\"");

        assertEquals("C:\\Books", o.startPath());
        assertFalse(o.debug());
        assertTrue(unknown.isEmpty());
    }

    @Test
    void usageMentionsEveryOption() {
        for (String option : List.of("--debug", "--path", "--help")) {
            assertTrue(AppOptions.USAGE.contains(option), option);
        }
    }
}

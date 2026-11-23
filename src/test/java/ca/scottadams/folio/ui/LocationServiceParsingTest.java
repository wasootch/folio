package ca.scottadams.folio.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocationServiceParsingTest {

    @Test
    void parsesServersFromNetView() {
        String output = """
                Server Name            Remark

                -------------------------------------------------------------------------------
                \\\\SERVER_NAME         Home NAS
                \\\\DESKTOP-ABC123
                \\\\Media-Box           Living room
                The command completed successfully.
                """;

        assertEquals(List.of("SERVER_NAME", "DESKTOP-ABC123", "Media-Box"), LocationService.parseServers(output));
    }

    @Test
    void noServersInTheOutputGivesAnEmptyList() {
        assertEquals(List.of(), LocationService.parseServers("System error 6118 has occurred.\n"));
        assertEquals(List.of(), LocationService.parseServers(""));
    }

    @Test
    void handlesWindowsLineEndings() {
        assertEquals(List.of("A", "B"), LocationService.parseServers("\\\\A  x\r\n\\\\B\r\n"));
    }

    @Test
    void parsesSharesAndSkipsAdministrativeSharesAndPrinters() {
        String output = """
                Shared resources at \\\\SERVER_NAME

                Share name   Type   Used as  Comment

                -------------------------------------------------------------------------------
                Books        Disk            Ebook library
                Photo Share  Disk
                ADMIN$       Disk            Remote Admin
                C$           Disk            Default share
                Laser        Print           Office printer
                The command completed successfully.
                """;

        assertEquals(List.of("Books", "Photo Share"), LocationService.parseShares(output));
    }

    @Test
    void sharesBeforeTheTableRuleAreIgnored() {
        assertEquals(List.of(), LocationService.parseShares("Books  Disk\n"));
    }
}

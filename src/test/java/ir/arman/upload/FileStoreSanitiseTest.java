package ir.arman.upload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task 9.1: {@link FileStore#sanitise} on its own, with no HTTP in the way.
 *
 * <p>It is tested directly because it is the whole of the defence around a
 * client-supplied filename, and because it is also the validator the serve route uses --
 * a name is servable exactly when this function returns it unchanged. A plain unit test
 * pins that property; the HTTP tests in {@link ir.arman.api.UploadResourceTest} then only
 * have to show it is actually wired in.
 */
class FileStoreSanitiseTest {

    @Test
    void anOrdinaryNameIsLeftAlone() {
        assertEquals("report.pdf", FileStore.sanitise("report.pdf"));
        assertEquals("annual-report_2026.v2.pdf",
                FileStore.sanitise("annual-report_2026.v2.pdf"));
    }

    @Test
    void persianSurvivesIntact() {
        assertEquals("گزارش-هفتگی.pdf", FileStore.sanitise("گزارش-هفتگی.pdf"));
        assertEquals("مستندات.txt", FileStore.sanitise("مستندات.txt"));
    }

    @Test
    void directoriesAreStripped() {
        assertEquals("passwd", FileStore.sanitise("../../etc/passwd"));
        assertEquals("secret.txt", FileStore.sanitise("C:\\Users\\admin\\secret.txt"));
        assertEquals("report.pdf", FileStore.sanitise("/absolute/report.pdf"));
    }

    @Test
    void aLeadingDotIsRemoved() {
        assertEquals("bashrc", FileStore.sanitise(".bashrc"));
        assertEquals("hidden.txt", FileStore.sanitise("...hidden.txt"));
    }

    @Test
    void aNameThatSanitisesToNothingFallsBackRatherThanReturningEmpty() {
        assertEquals("file", FileStore.sanitise("../.."));
        assertEquals("file", FileStore.sanitise("."));
        assertEquals("file", FileStore.sanitise(""));
        assertEquals("file", FileStore.sanitise("   "));
        assertEquals("file", FileStore.sanitise(null));
    }

    @Test
    void everythingElseBecomesAnUnderscore() {
        // Spaces, quotes, semicolons, newlines: legal in a filename on Linux, and every
        // one of them a problem in a url or a Content-Disposition header.
        assertEquals("my_report.pdf", FileStore.sanitise("my report.pdf"));
        assertEquals("a_b_c.txt", FileStore.sanitise("a\"b;c.txt"));
        assertEquals("line_break.txt", FileStore.sanitise("line\nbreak.txt"));
        assertEquals("_.txt", FileStore.sanitise("\u0000.txt"));
    }

    @Test
    void aLongNameIsTruncatedButKeepsItsExtension() {
        String name = "x".repeat(400) + ".pdf";
        String sanitised = FileStore.sanitise(name);

        assertEquals(104, sanitised.length(), "100 for the base plus '.pdf'");
        assertEquals(".pdf", sanitised.substring(sanitised.length() - 4));
    }

    @Test
    void sanitisingIsIdempotent() {
        // The serve route relies on this: it accepts a name only if sanitising leaves it
        // unchanged, which is only a usable check if the output is always a fixed point.
        for (String input : new String[]{
                "../../etc/passwd", ".bashrc", "my report.pdf", "گزارش-هفتگی.pdf", "../.."}) {
            String once = FileStore.sanitise(input);
            assertEquals(once, FileStore.sanitise(once), "not a fixed point: " + input);
        }
    }
}

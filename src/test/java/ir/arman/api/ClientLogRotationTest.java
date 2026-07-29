package ir.arman.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 10.1: the half of "write it to a file" that is easy to leave out.
 *
 * <p>Nothing else in this project would ever stop {@code app.log} growing -- there is no
 * logging stack in compose to collect or trim it, and the container filesystem is where it
 * lives. So rotation is not an extra; without it the decision to use files is a decision
 * to fill a disk eventually.
 *
 * <p>The cap is lowered to 1 KiB here rather than writing 10 MiB of entries. That costs a
 * second Quarkus boot, which is why this is its own class.
 */
@QuarkusTest
@TestProfile(ClientLogRotationTest.TinyLogFiles.class)
class ClientLogRotationTest {

    public static class TinyLogFiles implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.logs.max-bytes", "1024");
        }
    }

    private static final Path LOGS = Path.of("target/logs");
    private static final Path APP_LOG = LOGS.resolve("app.log");
    private static final Path ROTATED = LOGS.resolve("app.log.1");

    @Inject
    Pool pool;

    private String adminToken;

    @BeforeEach
    void signInAndStartFromEmptyFiles() throws IOException {
        adminToken = given().contentType(ContentType.JSON)
                .body(Map.of("studentId", "99100112", "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");
        clearLogs();
    }

    @AfterEach
    void removeWhatTheseTestsMade() throws IOException {
        clearLogs();
        pool.query("DELETE FROM refresh_tokens").execute().await().indefinitely();
    }

    @Test
    void atTheCapTheFileIsRolledAsideAndAFreshOneStarts() throws IOException {
        // Sent until the roll happens and then stopped, rather than a fixed count: with
        // one generation kept, a second roll genuinely does discard the first batch, so a
        // count chosen by guesswork would be testing arithmetic about entry sizes instead
        // of testing rotation.
        int sent = 0;
        while (Files.notExists(ROTATED) && sent < 100) {
            send("entry number " + sent + " " + "x".repeat(100));
            sent++;
        }

        assertTrue(Files.exists(ROTATED), "app.log.1 should exist once the cap was crossed");
        assertTrue(Files.size(APP_LOG) < Files.size(ROTATED),
                "the live file restarted rather than continuing to grow");

        // Within one rotation nothing is dropped: every entry is in one file or the other.
        long total = Files.readAllLines(APP_LOG, StandardCharsets.UTF_8).size()
                + Files.readAllLines(ROTATED, StandardCharsets.UTF_8).size();
        assertEquals(sent, total, "the first rotation must not lose an entry");
    }

    @Test
    void onlyOneGenerationIsKept() throws IOException {
        // Enough to roll over more than once. The old app.log.1 is replaced, never
        // promoted to .2 -- the disk cost stays bounded at twice the cap per file.
        for (int i = 0; i < 40; i++) {
            send("entry number " + i + " " + "x".repeat(100));
        }

        assertTrue(Files.exists(ROTATED));
        assertTrue(Files.notExists(LOGS.resolve("app.log.2")),
                "a second generation would mean growth was only slowed, not bounded");
    }

    private void send(String message) {
        given().header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("level", "info", "message", message))
                .when().post("/api/logs")
                .then().statusCode(200);
    }

    private static void clearLogs() throws IOException {
        Files.deleteIfExists(APP_LOG);
        Files.deleteIfExists(ROTATED);
        Files.deleteIfExists(LOGS.resolve("error.log"));
        Files.deleteIfExists(LOGS.resolve("error.log.1"));
    }
}

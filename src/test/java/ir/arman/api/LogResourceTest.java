package ir.arman.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 10.1: POST /api/logs -- the frontend writing to the server's log files.
 *
 * <p>The tests read the files back rather than trusting the 200, because the route
 * answers 200 even when the write failed (a client can do nothing with a disk error), so
 * the status code on its own proves nothing about whether anything was recorded.
 *
 * <p>Two of these are about the file format rather than the API, and they are the point of
 * the class: one entry must be exactly one line, and a message carrying a newline must not
 * be able to forge a second entry.
 */
@QuarkusTest
class LogResourceTest {

    /** components/responses/Unauthorized, verbatim from the spec. */
    private static final String UNAUTHORIZED = "دسترسی غیرمجاز. لطفا دوباره وارد شوید.";

    /** components/responses/Forbidden, verbatim from the spec. */
    private static final String FORBIDDEN = "این عملیات فقط برای مدیر مجاز است.";

    /** components/responses/BadRequest, verbatim from the spec. */
    private static final String BAD_REQUEST = "اطلاعات ورودی نامعتبر است.";

    /** The seed's two roles. usr_102 is an admin, usr_101 is not. */
    private static final String ADMIN_STUDENT_ID = "99100112";
    private static final String ADMIN_USER_ID = "usr_102";
    private static final String STUDENT_STUDENT_ID = "99100111";

    private static final Path LOGS = Path.of("target/logs");
    private static final Path APP_LOG = LOGS.resolve("app.log");
    private static final Path ERROR_LOG = LOGS.resolve("error.log");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    Pool pool;

    private String adminToken;

    @BeforeEach
    void signInAndStartFromEmptyFiles() throws IOException {
        adminToken = tokenFor(ADMIN_STUDENT_ID);
        clearLogs();
    }

    @AfterEach
    void removeWhatTheseTestsMade() throws IOException {
        clearLogs();
        pool.query("DELETE FROM refresh_tokens").execute().await().indefinitely();
    }

    // ------------------------------------------------------------------ what gets written

    @Test
    void anAcceptedEntryAnswersWithTheSpecsSuccessBody() {
        given().header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("level", "info", "message", "dashboard mounted"))
                .when().post("/api/logs")
                .then().statusCode(200)
                .body("success", is(true))
                .body("message", is("عملیات با موفقیت انجام شد."));
    }

    @Test
    void theEntryCarriesTheTimestampLevelUserAndMessage() throws IOException {
        send("error", "Failed to render chart component",
                Map.of("component", "DashboardChart"));

        JsonNode entry = onlyEntryIn(APP_LOG);
        assertEquals(Set.of("timestamp", "level", "userId", "message", "context"),
                fieldNames(entry));
        assertEquals("error", entry.get("level").asText());
        assertEquals("Failed to render chart component", entry.get("message").asText());
        assertEquals("DashboardChart", entry.get("context").get("component").asText());
        assertTrue(entry.get("timestamp").asText().endsWith("Z"),
                "an ISO instant, like every other timestamp this API emits");
    }

    @Test
    void theUserIsTakenFromTheTokenAndNotFromTheBody() throws IOException {
        // The spec's own example puts a userId inside context. That is the client's
        // claim about itself and it stays in context untouched; the userId the entry is
        // attributed to is the one that signed the request.
        send("info", "who am i", Map.of("userId", "usr_999"));

        JsonNode entry = onlyEntryIn(APP_LOG);
        assertEquals(ADMIN_USER_ID, entry.get("userId").asText());
        assertEquals("usr_999", entry.get("context").get("userId").asText(),
                "what the client said about itself is kept, just not believed");
    }

    @Test
    void contextIsOmittedWhenThereIsNone() throws IOException {
        send("info", "no context here", null);

        assertEquals(Set.of("timestamp", "level", "userId", "message"),
                fieldNames(onlyEntryIn(APP_LOG)));
    }

    @Test
    void aPersianMessageIsWrittenAsPersian() throws IOException {
        send("warn", "خطا در بارگذاری نمودار", null);

        assertEquals("خطا در بارگذاری نمودار", onlyEntryIn(APP_LOG).get("message").asText());
    }

    // ------------------------------------------------------------- which file it lands in

    @Test
    void anErrorGoesToBothFiles() throws IOException {
        send("error", "boom", null);

        assertEquals("boom", onlyEntryIn(APP_LOG).get("message").asText());
        assertEquals("boom", onlyEntryIn(ERROR_LOG).get("message").asText());
    }

    @Test
    void infoAndWarnStayOutOfTheErrorFile() throws IOException {
        send("info", "just so you know", null);
        send("warn", "careful", null);

        assertEquals(2, linesOf(APP_LOG).size());
        assertFalse(Files.exists(ERROR_LOG),
                "error.log must not even be created by a non-error entry");
    }

    // ------------------------------------------------------------------- the file format

    @Test
    void oneEntryIsExactlyOneLine() throws IOException {
        send("info", "first", null);
        send("info", "second", null);
        send("info", "third", null);

        List<String> lines = linesOf(APP_LOG);
        assertEquals(3, lines.size());
        for (String line : lines) {
            JSON.readTree(line);  // throws if a line is not a complete JSON document
        }
    }

    @Test
    void aMessageContainingNewlinesCannotForgeASecondEntry() throws IOException {
        // Written by the serialiser rather than by concatenation precisely for this: a
        // caller must not be able to append a line that nobody sent. Anything reading the
        // file counts entries by counting lines.
        send("info", "real entry\n{\"level\":\"error\",\"message\":\"forged\"}", null);

        List<String> lines = linesOf(APP_LOG);
        assertEquals(1, lines.size(), "the newline must be escaped, not honoured");
        assertTrue(onlyEntryIn(APP_LOG).get("message").asText().contains("forged"),
                "the text is kept verbatim inside the message -- it just is not a line");
    }

    @Test
    void anOversizedContextIsReplacedRatherThanCostingTheEntry() throws IOException {
        Map<String, Object> huge = new HashMap<>();
        for (int i = 0; i < 500; i++) {
            huge.put("key" + i, "x".repeat(50));
        }

        send("error", "context too big", huge);

        JsonNode entry = onlyEntryIn(APP_LOG);
        assertEquals("context too big", entry.get("message").asText(),
                "the message is the part worth keeping");
        assertTrue(entry.get("context").get("_truncated").asBoolean());
    }

    // ------------------------------------------------------------------------- refusals

    @Test
    void aStudentIsForbidden() {
        given().header("Authorization", "Bearer " + tokenFor(STUDENT_STUDENT_ID))
                .contentType(ContentType.JSON)
                .body(Map.of("level", "error", "message", "my dashboard broke"))
                .when().post("/api/logs")
                .then().statusCode(403)
                .body("success", is(false))
                .body("message", is(FORBIDDEN));
    }

    @Test
    void aForbiddenEntryIsNotWrittenAnyway() throws IOException {
        given().header("Authorization", "Bearer " + tokenFor(STUDENT_STUDENT_ID))
                .contentType(ContentType.JSON)
                .body(Map.of("level", "error", "message", "my dashboard broke"))
                .when().post("/api/logs")
                .then().statusCode(403);

        assertFalse(Files.exists(APP_LOG), "a refused request must leave no trace");
    }

    @Test
    void withoutATokenItIsAFourOhOne() {
        given().contentType(ContentType.JSON)
                .body(Map.of("level", "error", "message", "anonymous"))
                .when().post("/api/logs")
                .then().statusCode(401)
                .body("success", is(false))
                .body("message", is(UNAUTHORIZED));
    }

    @Test
    void anUndocumentedLevelIsRefusedWithTheAcceptedSetNamed() {
        given().header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("level", "fatal", "message", "everything is on fire"))
                .when().post("/api/logs")
                .then().statusCode(400)
                .body("success", is(false))
                .body("message", is(BAD_REQUEST))
                .body("errors", contains("level: must be one of [info, warn, error], was: fatal"));
    }

    @Test
    void aMissingLevelIsRefused() {
        given().header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("message", "no level"))
                .when().post("/api/logs")
                .then().statusCode(400)
                .body("message", is(BAD_REQUEST));
    }

    @Test
    void aBlankMessageIsRefused() {
        given().header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("level", "info", "message", "   "))
                .when().post("/api/logs")
                .then().statusCode(400)
                .body("message", is(BAD_REQUEST));
    }

    @Test
    void aMessageOverTheCapIsRefused() {
        given().header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("level", "info", "message", "x".repeat(4097)))
                .when().post("/api/logs")
                .then().statusCode(400)
                .body("message", is(BAD_REQUEST));
    }

    @Test
    void aMessageExactlyAtTheCapIsAccepted() {
        given().header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("level", "info", "message", "x".repeat(4096)))
                .when().post("/api/logs")
                .then().statusCode(200);
    }

    @Test
    void theSpecDeclaresOnlyPostOnThisRoute() {
        given().header("Authorization", "Bearer " + adminToken)
                .when().get("/api/logs")
                .then().statusCode(405);
    }

    // ------------------------------------------------------------------------- helpers

    private void send(String level, String message, Map<String, Object> context) {
        Map<String, Object> body = new HashMap<>();
        body.put("level", level);
        body.put("message", message);
        if (context != null) {
            body.put("context", context);
        }

        given().header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/api/logs")
                .then().statusCode(200);
    }

    private static JsonNode onlyEntryIn(Path file) throws IOException {
        List<String> lines = linesOf(file);
        assertEquals(1, lines.size(), "expected one entry in " + file + ", got " + lines);
        return JSON.readTree(lines.getFirst());
    }

    private static List<String> linesOf(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    private static Set<String> fieldNames(JsonNode entry) {
        Set<String> names = new java.util.LinkedHashSet<>();
        entry.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void clearLogs() throws IOException {
        Files.deleteIfExists(APP_LOG);
        Files.deleteIfExists(ERROR_LOG);
        Files.deleteIfExists(LOGS.resolve("app.log.1"));
        Files.deleteIfExists(LOGS.resolve("error.log.1"));
    }

    private static String tokenFor(String studentId) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("studentId", studentId, "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");
    }
}

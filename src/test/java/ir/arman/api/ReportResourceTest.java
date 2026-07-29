package ir.arman.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tasks 6.1 and 6.2: /api/reports.
 *
 * <p>The decision this class exists to pin is the scope of the history: an admin reads
 * every report, anyone else reads only their own. The spec does not say so, which is
 * exactly why it is asserted from both sides here.
 *
 * <p>Every report these tests file carries a weekTitle starting with the marker below,
 * and the cleanup deletes by it. The 30 seeded reports are read but never written.
 */
@QuarkusTest
class ReportResourceTest {

    /** components/responses/BadRequest, verbatim from the spec. */
    private static final String BAD_REQUEST = "اطلاعات ورودی نامعتبر است.";

    /** components/responses/Unauthorized, verbatim from the spec. */
    private static final String UNAUTHORIZED = "دسترسی غیرمجاز. لطفا دوباره وارد شوید.";

    private static final String MARKER = "[test-6x] ";

    /** usr_101, a student. The seed gives every account the same password. */
    private static final String STUDENT = "99100111";

    /** usr_103, a second student -- for what one student may not see of another. */
    private static final String OTHER_STUDENT = "99100113";

    /** usr_102, one of the two seeded admins. No account can become one through the API. */
    private static final String ADMIN = "99100112";

    private static final String THROWAWAY_PREFIX = "test-report-";

    @Inject
    Pool pool;

    private String studentToken;

    @BeforeEach
    void signIn() {
        studentToken = tokenFor(STUDENT);
    }

    @AfterEach
    void removeWhatTheseTestsMade() {
        pool.preparedQuery("DELETE FROM weekly_reports WHERE week_title LIKE $1")
                .execute(Tuple.of(MARKER + "%")).await().indefinitely();
        // Throwaway accounts, and their reports with them: weekly_reports.user_id is
        // ON DELETE CASCADE.
        pool.preparedQuery("DELETE FROM refresh_tokens WHERE user_id IN "
                        + "(SELECT id FROM users WHERE student_id LIKE $1)")
                .execute(Tuple.of(THROWAWAY_PREFIX + "%")).await().indefinitely();
        pool.preparedQuery("DELETE FROM users WHERE student_id LIKE $1")
                .execute(Tuple.of(THROWAWAY_PREFIX + "%")).await().indefinitely();
        pool.query("DELETE FROM refresh_tokens").execute().await().indefinitely();
    }

    private static String tokenFor(String studentId) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("studentId", studentId, "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");
    }

    private static RequestSpecification with(String token) {
        return given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token);
    }

    private RequestSpecification asTheStudent() {
        return with(studentToken);
    }

    private static Map<String, Object> report(String weekTitle) {
        Map<String, Object> body = new HashMap<>();
        body.put("weekTitle", MARKER + weekTitle);
        body.put("hoursWorked", 42);
        body.put("tasksCompleted", 8);
        body.put("achievements", "تکمیل بخش ورود کاربران");
        body.put("challenges", "هماهنگی SSE");
        body.put("nextWeekPlan", "طراحی داشبورد");
        return body;
    }

    // ---- 6.1 GET /api/reports: who sees what -----------------------------------------

    @Test
    void anAdminSeesEveryReport() {
        with(tokenFor(ADMIN)).when().get("/api/reports")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(30))
                .body("id", hasItem("rep_1"))
                .body("userId", hasItem("usr_101"))
                .body("userId", hasItem("usr_130"));
    }

    @Test
    void aStudentSeesOnlyTheirOwn() {
        // The decision behind this route. Under the other reading, every student would
        // read every other student's hours and difficulties.
        asTheStudent().when().get("/api/reports")
                .then()
                .statusCode(200)
                .body("userId", everyItem(is("usr_101")))
                .body("id", hasItem("rep_1"));
    }

    @Test
    void oneStudentsReportIsInvisibleToAnother() {
        String id = asTheStudent().body(report("private"))
                .when().post("/api/reports")
                .then().statusCode(201).extract().path("id");

        with(tokenFor(OTHER_STUDENT)).when().get("/api/reports")
                .then().statusCode(200).body("id", not(hasItem(id)));

        // But the admin does see it.
        with(tokenFor(ADMIN)).when().get("/api/reports")
                .then().statusCode(200).body("id", hasItem(id));
    }

    @Test
    void theHistoryIsNewestFirst() {
        List<String> submitted = with(tokenFor(ADMIN)).when().get("/api/reports")
                .then().statusCode(200).extract().jsonPath().getList("submittedAt");

        for (int i = 1; i < submitted.size(); i++) {
            assertTrue(submitted.get(i - 1).compareTo(submitted.get(i)) >= 0,
                    "out of order at " + i + ": " + submitted.get(i - 1) + " then "
                            + submitted.get(i));
        }
    }

    @Test
    void everyFieldOfTheSpecsSchemaIsPresent() {
        asTheStudent().when().get("/api/reports")
                .then()
                .statusCode(200)
                .body("find { it.id == 'rep_1' }.userId", is("usr_101"))
                .body("find { it.id == 'rep_1' }.userName", is("علی محمدی"))
                .body("find { it.id == 'rep_1' }.weekTitle", is("هفته دوم مرداد ۱۴۰۵"))
                .body("find { it.id == 'rep_1' }.hoursWorked", is(42.00f))
                .body("find { it.id == 'rep_1' }.tasksCompleted", is(8))
                .body("find { it.id == 'rep_1' }.submittedAt",
                        matchesRegex("\\d{4}-\\d{2}-\\d{2}T.*Z"));
    }

    @Test
    void theHistoryNeedsAToken() {
        given().when().get("/api/reports")
                .then().statusCode(401).body("message", is(UNAUTHORIZED));
    }

    // ---- 6.2 POST /api/reports -------------------------------------------------------

    @Test
    void submitReturns201WithTheAuthorAndTimestampStampedByTheServer() {
        asTheStudent().body(report("filed"))
                .when().post("/api/reports")
                .then()
                .statusCode(201)
                .contentType(ContentType.JSON)
                .body("id", matchesRegex("rep_\\d+"))
                .body("userId", is("usr_101"))
                .body("userName", is("علی محمدی"))
                .body("weekTitle", is(MARKER + "filed"))
                .body("hoursWorked", is(42.00f))
                .body("tasksCompleted", is(8))
                .body("achievements", is("تکمیل بخش ورود کاربران"))
                .body("submittedAt", matchesRegex("\\d{4}-\\d{2}-\\d{2}T.*Z"));
    }

    @Test
    void theAuthorAndTheTimestampCannotBeForgedThroughTheBody() {
        // CreateReportRequest carries none of these three, so they are ignored rather
        // than honoured. Without that, any account could file in anyone's name.
        Map<String, Object> forged = report("forged");
        forged.put("userId", "usr_130");
        forged.put("userName", "شخص دیگر");
        forged.put("submittedAt", "1999-01-01T00:00:00Z");

        String stamped = asTheStudent().body(forged)
                .when().post("/api/reports")
                .then()
                .statusCode(201)
                .body("userId", is("usr_101"))
                .body("userName", is("علی محمدی"))
                .extract().path("submittedAt");

        assertTrue(stamped.startsWith("20"), "expected a server timestamp, got " + stamped);
    }

    @Test
    void aFiledReportIsInTheAuthorsOwnHistory() {
        String id = asTheStudent().body(report("mine"))
                .when().post("/api/reports").then().statusCode(201).extract().path("id");

        asTheStudent().when().get("/api/reports")
                .then().statusCode(200).body("id", hasItem(id));
    }

    @Test
    void userNameIsASnapshotAndDoesNotFollowARename() {
        // The entity comment says the name is copied rather than joined; this is what
        // that buys. Run on a throwaway account, since it renames the author.
        String studentId = THROWAWAY_PREFIX + UUID.randomUUID();
        String token = given().contentType(ContentType.JSON)
                .body(Map.of("name", "نام اول", "studentId", studentId,
                        "password", "Password123"))
                .when().post("/api/auth/register")
                .then().statusCode(201).extract().path("accessToken");

        String id = with(token).body(report("snapshot"))
                .when().post("/api/reports")
                .then().statusCode(201).body("userName", is("نام اول"))
                .extract().path("id");

        with(token).body(Map.of("name", "نام دوم"))
                .when().put("/api/users/me").then().statusCode(200);

        with(token).when().get("/api/reports")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + id + "' }.userName", is("نام اول"));
    }

    @Test
    void onlyTheThreeRequiredFieldsAreNeededAndTheRestComeBackNull() {
        asTheStudent().body(Map.of("weekTitle", MARKER + "bare",
                        "hoursWorked", 10, "tasksCompleted", 1))
                .when().post("/api/reports")
                .then()
                .statusCode(201)
                .body("achievements", is(nullValue()))
                .body("challenges", is(nullValue()))
                .body("nextWeekPlan", is(nullValue()));
    }

    @Test
    void eachRequiredFieldIsRejectedWhenMissing() {
        for (String missing : List.of("weekTitle", "hoursWorked", "tasksCompleted")) {
            Map<String, Object> incomplete = report("missing " + missing);
            incomplete.remove(missing);

            asTheStudent().body(incomplete)
                    .when().post("/api/reports")
                    .then()
                    .statusCode(400)
                    .body("message", is(BAD_REQUEST))
                    .body("errors", hasItem(matchesRegex(missing + ": .*")));
        }
    }

    @Test
    void negativeWorkIsRejected() {
        // The columns carry no CHECK, so this is the only place it is caught -- without
        // it, hoursWorked: -40 is stored and read back.
        asTheStudent().body(withField("negative hours", "hoursWorked", -40))
                .when().post("/api/reports")
                .then().statusCode(400).body("errors",
                        hasItem("hoursWorked: must be greater than or equal to 0"));

        asTheStudent().body(withField("negative tasks", "tasksCompleted", -1))
                .when().post("/api/reports")
                .then().statusCode(400).body("message", is(BAD_REQUEST));
    }

    @Test
    void aWeekOfNoWorkIsAllowed() {
        // Zero is a real answer, and rejecting it would make an honest report impossible.
        Map<String, Object> nothing = withField("idle", "hoursWorked", 0);
        nothing.put("tasksCompleted", 0);

        asTheStudent().body(nothing)
                .when().post("/api/reports")
                .then().statusCode(201).body("tasksCompleted", is(0));
    }

    @Test
    void halfHoursSurviveTheRoundTrip() {
        // NUMERIC(6,2), not an integer: a double would round these away.
        String id = asTheStudent().body(withField("halves", "hoursWorked", 42.5))
                .when().post("/api/reports")
                .then().statusCode(201).body("hoursWorked", is(42.50f))
                .extract().path("id");

        asTheStudent().when().get("/api/reports")
                .then().body("find { it.id == '" + id + "' }.hoursWorked", is(42.50f));
    }

    @Test
    void moreThanTwoDecimalsIsRejectedRatherThanRoundedIntoTheColumn() {
        asTheStudent().body(withField("precise", "hoursWorked", 42.555))
                .when().post("/api/reports")
                .then().statusCode(400).body("message", is(BAD_REQUEST));
    }

    @Test
    void hoursBeyondWhatTheColumnHoldsAreRejected() {
        // NUMERIC(6,2) tops out at 9999.99. Past that Postgres raises, which would be a
        // 500 for what is a bad request.
        asTheStudent().body(withField("enormous", "hoursWorked", 99999))
                .when().post("/api/reports")
                .then().statusCode(400).body("message", is(BAD_REQUEST));
    }

    @Test
    void aWeekTitleLongerThanItsColumnIsRejected() {
        asTheStudent().body(withField("long", "weekTitle", MARKER + "ه".repeat(300)))
                .when().post("/api/reports")
                .then().statusCode(400).body("message", is(BAD_REQUEST));
    }

    @Test
    void twoReportsForTheSameWeekAreAllowed() {
        // No uniqueness on weekTitle anywhere in the spec, and refusing the second would
        // make a correction impossible to file.
        Map<String, Object> same = report("same week");
        asTheStudent().body(same).when().post("/api/reports").then().statusCode(201);
        asTheStudent().body(same).when().post("/api/reports").then().statusCode(201);

        asTheStudent().when().get("/api/reports")
                .then().body("findAll { it.weekTitle == '" + MARKER + "same week' }", hasSize(2));
    }

    @Test
    void submittingNeedsAToken() {
        given().contentType(ContentType.JSON).body(report("unauthenticated"))
                .when().post("/api/reports")
                .then().statusCode(401).body("message", is(UNAUTHORIZED));

        with(tokenFor(ADMIN)).when().get("/api/reports")
                .then().body("weekTitle", not(hasItem(MARKER + "unauthenticated")));
    }

    // ---- the seeded fixture is not disturbed -----------------------------------------

    @Test
    void theThirtySeededReportsAreLeftAlone() {
        asTheStudent().body(report("noise")).when().post("/api/reports").then().statusCode(201);

        Long seeded = pool.query("SELECT count(*) FROM weekly_reports WHERE id ~ '^rep_[0-9]+$'"
                        + " AND week_title NOT LIKE '[test-%'")
                .execute().await().indefinitely().iterator().next().getLong(0);
        assertEquals(30L, seeded);
    }

    private static Map<String, Object> withField(String title, String field, Object value) {
        Map<String, Object> body = report(title);
        body.put(field, value);
        return body;
    }
}

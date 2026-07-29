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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 5.5, and the two foreign keys and the date column behind /api/tasks.
 *
 * <p>The enum mechanism itself was built in task 3.3 and is covered by
 * {@link EnumEnforcementTest}; what is asserted here is that the task routes actually use
 * it -- on the body <em>and</em> on the query parameter, where the JAX-RS default would
 * have been a 404 rather than a 400.
 *
 * <p>The rest is what task.md recorded as deferred from the 1.5 audit: an unknown
 * assignee violates `task_assignees`' foreign key, and `due_date` is a real DATE while
 * the spec types `dueDate` as a string. Both would otherwise surface as a 500.
 */
@QuarkusTest
class TaskValidationTest {

    private static final String BAD_REQUEST = "اطلاعات ورودی نامعتبر است.";

    private static final String MARKER = "[test-55] ";

    @Inject
    Pool pool;

    private String token;

    @BeforeEach
    void signIn() {
        token = given().contentType(ContentType.JSON)
                .body(Map.of("studentId", "99100111", "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");
    }

    @AfterEach
    void removeWhatTheseTestsMade() {
        pool.preparedQuery("DELETE FROM tasks WHERE title LIKE $1")
                .execute(Tuple.of(MARKER + "%")).await().indefinitely();
        pool.query("DELETE FROM refresh_tokens").execute().await().indefinitely();
    }

    private RequestSpecification asAUser() {
        return given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token);
    }

    private static Map<String, Object> task(String title) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", "proj_1");
        body.put("title", MARKER + title);
        return body;
    }

    private static Map<String, Object> with(String title, String field, Object value) {
        Map<String, Object> body = task(title);
        body.put(field, value);
        return body;
    }

    private long taskCount() {
        return pool.query("SELECT count(*) FROM tasks").execute()
                .await().indefinitely().iterator().next().getLong(0);
    }

    // ---- 5.5 the enums, in the body --------------------------------------------------

    @Test
    void anUndocumentedStatusIsRejectedWithTheAcceptedSetNamed() {
        asAUser().body(with("bad status", "status", "archived"))
                .when().post("/api/tasks")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("message", is(BAD_REQUEST))
                .body("errors",
                        contains("status: must be one of [todo, in_progress, review, done],"
                                + " was: archived"));
    }

    @Test
    void anUndocumentedPriorityIsRejectedTheSameWay() {
        asAUser().body(with("bad priority", "priority", "urgent"))
                .when().post("/api/tasks")
                .then()
                .statusCode(400)
                .body("errors", contains("priority: must be one of [low, medium, high],"
                        + " was: urgent"));
    }

    @Test
    void theEnumsAreCaseSensitiveBecauseTheSpecWritesThemInLowerCase() {
        asAUser().body(with("shouty", "status", "TODO"))
                .when().post("/api/tasks")
                .then().statusCode(400).body("message", is(BAD_REQUEST));
    }

    @Test
    void everyDocumentedStatusIsAccepted() {
        for (String status : List.of("todo", "in_progress", "review", "done")) {
            asAUser().body(with("status " + status, "status", status))
                    .when().post("/api/tasks")
                    .then().statusCode(201).body("status", is(status));
        }
    }

    @Test
    void everyDocumentedPriorityIsAcceptedAndSoIsNone() {
        for (String priority : List.of("low", "medium", "high")) {
            asAUser().body(with("priority " + priority, "priority", priority))
                    .when().post("/api/tasks")
                    .then().statusCode(201).body("priority", is(priority));
        }

        // priority is optional in the spec and the column is nullable.
        asAUser().body(task("no priority"))
                .when().post("/api/tasks")
                .then().statusCode(201).body("priority", is(nullValue()));
    }

    @Test
    void theEnumsAreEnforcedOnUpdateToo() {
        String id = asAUser().body(task("to update"))
                .when().post("/api/tasks").then().statusCode(201).extract().path("id");

        asAUser().body(with("updated", "status", "blocked"))
                .when().put("/api/tasks/" + id)
                .then().statusCode(400).body("message", is(BAD_REQUEST));
    }

    // ---- 5.5 the enum, in the query parameter ----------------------------------------

    @Test
    void anUndocumentedStatusFilterIsABadRequestRatherThanANotFound() {
        // If `status` were declared as the enum, JAX-RS would answer a value it cannot
        // convert with a 404 -- telling a client the endpoint does not exist when what is
        // wrong is the filter. Hence the String parameter converted by hand.
        asAUser().when().get("/api/tasks?status=archived")
                .then()
                .statusCode(400)
                .body("message", is(BAD_REQUEST))
                .body("errors", contains("status: must be one of"
                        + " [todo, in_progress, review, done], was: archived"));
    }

    @Test
    void everyDocumentedStatusFilterIsAccepted() {
        for (String status : List.of("todo", "in_progress", "review", "done")) {
            asAUser().when().get("/api/tasks?status=" + status).then().statusCode(200);
        }
    }

    @Test
    void theDatabaseEnforcesTheSameSetsAsASecondLine() {
        // Belt and braces: if a route ever wrote a value straight through, these CHECK
        // constraints are what stops it reaching a column the client would then read back.
        String constraints = pool.query(
                        "SELECT string_agg(conname, ',') FROM pg_constraint"
                                + " WHERE conrelid = 'tasks'::regclass AND contype = 'c'")
                .execute().await().indefinitely().iterator().next().getString(0);

        assertTrue(constraints.contains("tasks_status_check"), constraints);
        assertTrue(constraints.contains("tasks_priority_check"), constraints);
    }

    // ---- the foreign keys ------------------------------------------------------------

    @Test
    void anUnknownProjectIdIsABadRequestNamingIt() {
        long before = taskCount();

        asAUser().body(with("orphan", "projectId", "proj_does_not_exist"))
                .when().post("/api/tasks")
                .then()
                .statusCode(400)
                .body("message", is(BAD_REQUEST))
                .body("errors", contains("projectId: no project with id proj_does_not_exist"));

        assertEquals(before, taskCount(), "nothing may be written when the request is refused");
    }

    @Test
    void anUnknownAssigneeIsABadRequestNamingIt() {
        long before = taskCount();

        asAUser().body(with("ghost assignee", "assignees", List.of("usr_101", "usr_nobody")))
                .when().post("/api/tasks")
                .then()
                .statusCode(400)
                .body("errors", contains("assignees: no user with id usr_nobody"));

        assertEquals(before, taskCount());
    }

    @Test
    void everyProblemIsReportedAtOnceRatherThanOneAtATime() {
        // A client fixing a form should not have to submit four times to find four faults.
        Map<String, Object> wrong = task("all wrong");
        wrong.put("projectId", "proj_nope");
        wrong.put("assignees", List.of("usr_nope"));
        wrong.put("dueDate", "yesterday");

        asAUser().body(wrong)
                .when().post("/api/tasks")
                .then()
                .statusCode(400)
                .body("errors", hasItem("projectId: no project with id proj_nope"))
                .body("errors", hasItem("assignees: no user with id usr_nope"))
                .body("errors",
                        hasItem("dueDate: must be a date of the form YYYY-MM-DD, was: yesterday"));
    }

    @Test
    void anUnknownAssigneeOnUpdateLeavesTheTaskAsItWas() {
        String id = asAUser().body(with("keeps its people", "assignees", List.of("usr_101")))
                .when().post("/api/tasks").then().statusCode(201).extract().path("id");

        asAUser().body(with("hijack", "assignees", List.of("usr_nobody")))
                .when().put("/api/tasks/" + id)
                .then().statusCode(400);

        asAUser().when().get("/api/tasks")
                .then()
                .body("find { it.id == '" + id + "' }.assignees", contains("usr_101"))
                .body("find { it.id == '" + id + "' }.title", is(MARKER + "keeps its people"));
    }

    // ---- dueDate is a date, not a timestamp ------------------------------------------

    @Test
    void aFullTimestampIsRejectedRatherThanQuietlyTruncated() {
        // Dropping the time part of 2026-08-15T23:30:00Z moves the deadline by a day for
        // anyone east of UTC, and the response would say 201 either way.
        asAUser().body(with("timestamped", "dueDate", "2026-08-15T23:30:00Z"))
                .when().post("/api/tasks")
                .then()
                .statusCode(400)
                .body("message", is(BAD_REQUEST))
                .body("errors", contains("dueDate: must be a date of the form YYYY-MM-DD,"
                        + " was: 2026-08-15T23:30:00Z"));
    }

    @Test
    void aDateThatDoesNotExistIsRejected() {
        // Right shape, wrong calendar -- caught by parsing rather than by a regex.
        asAUser().body(with("impossible", "dueDate", "2026-02-31"))
                .when().post("/api/tasks")
                .then().statusCode(400).body("message", is(BAD_REQUEST));
    }

    @Test
    void otherDateFormatsAreRejectedToo() {
        for (String bad : List.of("15/08/2026", "2026-8-15", "August 15, 2026", "2026")) {
            asAUser().body(with("format", "dueDate", bad))
                    .when().post("/api/tasks")
                    .then().statusCode(400).body("message", is(BAD_REQUEST));
        }
    }

    @Test
    void anEmptyDueDateMeansNoDueDateRatherThanABadRequest() {
        asAUser().body(with("blank date", "dueDate", ""))
                .when().post("/api/tasks")
                .then().statusCode(201).body("dueDate", is(nullValue()));
    }

    @Test
    void aJalaliDateIsAcceptedAsAGregorianOneAndIsNotConverted() {
        // Worth stating rather than leaving to be discovered: 1405-05-24 is a well-formed
        // ISO date in the year 1405, so nothing here can tell it apart from a mistake.
        // The API takes Gregorian dates only -- the calendar shown to the user is the
        // client's business, and converting here would guess at which calendar was meant.
        asAUser().body(with("jalali", "dueDate", "1405-05-24"))
                .when().post("/api/tasks")
                .then().statusCode(201).body("dueDate", is("1405-05-24"));
    }

    @Test
    void aValidDateSurvivesTheRoundTripUnchanged() {
        String id = asAUser().body(with("dated", "dueDate", "2026-12-01"))
                .when().post("/api/tasks")
                .then().statusCode(201).body("dueDate", is("2026-12-01"))
                .extract().path("id");

        asAUser().when().get("/api/tasks")
                .then().body("find { it.id == '" + id + "' }.dueDate", is("2026-12-01"));
    }

    // ---- nothing leaks into the seeded fixture ---------------------------------------

    @Test
    void aRefusedRequestNeverAppearsInTheList() {
        asAUser().body(with("never stored", "status", "archived"))
                .when().post("/api/tasks").then().statusCode(400);

        asAUser().when().get("/api/tasks")
                .then().body("title", not(hasItem(MARKER + "never stored")));
    }
}

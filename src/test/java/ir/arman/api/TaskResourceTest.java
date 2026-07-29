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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tasks 5.1 to 5.4: the four task routes. The enum and foreign-key rules are
 * {@link TaskValidationTest}.
 *
 * <p>Every task these tests create carries a title starting with the marker below, and the
 * cleanup deletes by that marker. The 30 seeded tasks are read but never written --
 * `RepositoryTest` and `SpecSchemaCoverageTest` both depend on them being untouched.
 */
@QuarkusTest
class TaskResourceTest {

    /** components/responses/NotFound, verbatim from the spec. */
    private static final String NOT_FOUND = "منبع مورد نظر پیدا نشد.";

    /** components/responses/BadRequest, verbatim from the spec. */
    private static final String BAD_REQUEST = "اطلاعات ورودی نامعتبر است.";

    /** components/responses/Unauthorized, verbatim from the spec. */
    private static final String UNAUTHORIZED = "دسترسی غیرمجاز. لطفا دوباره وارد شوید.";

    private static final String MARKER = "[test-5x] ";

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
        // task_assignees goes with it: the foreign key is ON DELETE CASCADE.
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
        body.put("description", "توضیح تسک");
        body.put("status", "todo");
        body.put("priority", "medium");
        body.put("assignees", List.of("usr_101"));
        body.put("dueDate", "2026-08-15");
        return body;
    }

    private String createTask(String title) {
        return asAUser().body(task(title))
                .when().post("/api/tasks")
                .then().statusCode(201)
                .extract().path("id");
    }

    // ---- 5.1 GET /api/tasks ----------------------------------------------------------

    @Test
    void listsTheSeededTasksWithEveryFieldOfTheSpecsSchema() {
        asAUser().when().get("/api/tasks")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(30))
                .body("find { it.id == 'task_1' }.projectId", is("proj_1"))
                .body("find { it.id == 'task_1' }.title", is("پیاده‌سازی صفحه ورود"))
                .body("find { it.id == 'task_1' }.description", is("طراحی فرم و اتصال به API"))
                .body("find { it.id == 'task_1' }.status", is("in_progress"))
                .body("find { it.id == 'task_1' }.priority", is("high"))
                .body("find { it.id == 'task_1' }.dueDate", is("2026-08-15"))
                .body("find { it.id == 'task_1' }.assignees",
                        containsInAnyOrder("usr_101", "usr_103"));
    }

    @Test
    void dueDateIsAPlainCalendarDateAndNeverATimestamp() {
        // The column is a DATE and the spec examples "2026-08-15". Pinned so it cannot
        // drift into an instant, which would break every date input on the client.
        asAUser().when().get("/api/tasks")
                .then()
                .statusCode(200)
                .body("findAll { it.dueDate != null }.dueDate",
                        everyItem(matchesRegex("\\d{4}-\\d{2}-\\d{2}")));
    }

    @Test
    void assigneesIsAlwaysAnArrayEvenWhenNobodyIsAssigned() {
        Map<String, Object> nobody = task("unassigned");
        nobody.remove("assignees");
        String id = asAUser().body(nobody)
                .when().post("/api/tasks").then().statusCode(201).extract().path("id");

        asAUser().when().get("/api/tasks")
                .then()
                .body("assignees", everyItem(notNullValue()))
                .body("find { it.id == '" + id + "' }.assignees", hasSize(0));
    }

    @Test
    void tasksWithoutADueDateComeLast() {
        Map<String, Object> undated = task("no due date");
        undated.remove("dueDate");
        asAUser().body(undated).when().post("/api/tasks").then().statusCode(201);

        List<String> dueDates = asAUser().when().get("/api/tasks")
                .then().statusCode(200).extract().jsonPath().getList("dueDate");

        int firstUndated = dueDates.indexOf(null);
        assertTrue(firstUndated >= 0, "the task just created has no due date");
        assertTrue(dueDates.subList(firstUndated, dueDates.size()).stream().allMatch(d -> d == null),
                "a dated task appeared after an undated one: " + dueDates);
    }

    @Test
    void filtersByProject() {
        asAUser().when().get("/api/tasks?projectId=proj_1")
                .then()
                .statusCode(200)
                .body("projectId", everyItem(is("proj_1")))
                .body("id", hasItem("task_1"))
                .body("id", not(hasItem("task_4")));
    }

    @Test
    void filtersByStatus() {
        asAUser().when().get("/api/tasks?status=done")
                .then()
                .statusCode(200)
                .body("status", everyItem(is("done")))
                .body("id", hasItem("task_2"));
    }

    @Test
    void theTwoFiltersCombineWithAnd() {
        asAUser().when().get("/api/tasks?projectId=proj_1&status=done")
                .then()
                .statusCode(200)
                .body("projectId", everyItem(is("proj_1")))
                .body("status", everyItem(is("done")))
                .body("id", hasItem("task_2"))
                .body("id", not(hasItem("task_1")));
    }

    @Test
    void anEmptyFilterMeansNoFilterRatherThanAnEmptyValue() {
        // What a form or a query-string builder sends for a filter nobody set. Reading it
        // literally would answer with [] and look like the tasks had vanished.
        int all = asAUser().when().get("/api/tasks")
                .then().statusCode(200).extract().jsonPath().getList("id").size();

        asAUser().when().get("/api/tasks?projectId=&status=")
                .then().statusCode(200).body("size()", is(all));
    }

    @Test
    void anUnknownProjectIdIsAnEmptyListRatherThanAnError() {
        // A filter that matches nothing, not a lookup of a missing resource.
        asAUser().when().get("/api/tasks?projectId=proj_does_not_exist")
                .then().statusCode(200).body("size()", is(0));
    }

    @Test
    void theListNeedsAToken() {
        given().when().get("/api/tasks")
                .then().statusCode(401).body("message", is(UNAUTHORIZED));
    }

    // ---- 5.2 POST /api/tasks ---------------------------------------------------------

    @Test
    void createReturns201WithAServerAssignedId() {
        asAUser().body(task("created"))
                .when().post("/api/tasks")
                .then()
                .statusCode(201)
                .contentType(ContentType.JSON)
                .body("id", matchesRegex("task_\\d+"))
                .body("projectId", is("proj_1"))
                .body("title", is(MARKER + "created"))
                .body("description", is("توضیح تسک"))
                .body("status", is("todo"))
                .body("priority", is("medium"))
                .body("assignees", contains("usr_101"))
                .body("dueDate", is("2026-08-15"));
    }

    @Test
    void statusDefaultsToTodoWhenTheRequestOmitsIt() {
        // CreateTaskRequest does not require status but Task does, and the column is
        // NOT NULL DEFAULT 'todo'.
        Map<String, Object> body = task("no status");
        body.remove("status");

        asAUser().body(body)
                .when().post("/api/tasks")
                .then().statusCode(201).body("status", is("todo"));
    }

    @Test
    void onlyProjectIdAndTitleAreRequiredAndTheRestComeBackEmpty() {
        asAUser().body(Map.of("projectId", "proj_1", "title", MARKER + "bare"))
                .when().post("/api/tasks")
                .then()
                .statusCode(201)
                .body("title", is(MARKER + "bare"))
                .body("description", is(nullValue()))
                .body("priority", is(nullValue()))
                .body("dueDate", is(nullValue()))
                .body("assignees", hasSize(0));
    }

    @Test
    void aCreatedTaskIsInTheListAfterwards() {
        String id = createTask("persisted");

        asAUser().when().get("/api/tasks")
                .then().statusCode(200).body("id", hasItem(id));
    }

    @Test
    void severalAssigneesAreStoredAndDuplicatesCountOnce() {
        asAUser().body(withAssignees("many", List.of("usr_101", "usr_102", "usr_101")))
                .when().post("/api/tasks")
                .then()
                .statusCode(201)
                .body("assignees", hasSize(2))
                .body("assignees", containsInAnyOrder("usr_101", "usr_102"));
    }

    @Test
    void aMissingTitleOrProjectIdIsTheSpecsBadRequest() {
        asAUser().body(Map.of("projectId", "proj_1"))
                .when().post("/api/tasks")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("message", is(BAD_REQUEST))
                .body("errors", hasItem("title: must not be blank"));

        asAUser().body(Map.of("title", MARKER + "no project"))
                .when().post("/api/tasks")
                .then()
                .statusCode(400)
                .body("errors", hasItem("projectId: must not be blank"));
    }

    @Test
    void aTitleLongerThanItsColumnIsRejectedRatherThanReachingPostgres() {
        Map<String, Object> tooLong = task("long");
        tooLong.put("title", MARKER + "ط".repeat(300));

        asAUser().body(tooLong)
                .when().post("/api/tasks")
                .then().statusCode(400).body("message", is(BAD_REQUEST));
    }

    @Test
    void creatingNeedsAToken() {
        given().contentType(ContentType.JSON).body(task("unauthenticated"))
                .when().post("/api/tasks")
                .then().statusCode(401).body("message", is(UNAUTHORIZED));

        asAUser().when().get("/api/tasks")
                .then().body("title", not(hasItem(MARKER + "unauthenticated")));
    }

    // ---- 5.3 PUT /api/tasks/{id} -----------------------------------------------------

    @Test
    void updateReplacesTheContentsAndKeepsTheIdentity() {
        String id = createTask("before");

        Map<String, Object> replacement = task("after");
        replacement.put("status", "review");
        replacement.put("priority", "high");
        replacement.put("assignees", List.of("usr_102"));
        replacement.put("dueDate", "2026-09-30");

        asAUser().body(replacement)
                .when().put("/api/tasks/" + id)
                .then()
                .statusCode(200)
                .body("id", is(id))
                .body("title", is(MARKER + "after"))
                .body("status", is("review"))
                .body("priority", is("high"))
                .body("assignees", contains("usr_102"))
                .body("dueDate", is("2026-09-30"));
    }

    @Test
    void omittedFieldsAreClearedIncludingTheAssignees() {
        // The decision behind this route: PUT takes CreateTaskRequest, the same schema
        // POST uses, so absent means null on both -- exactly as PUT /api/projects/{id}
        // behaves. Moving a task between columns by sending only projectId, title and
        // status therefore unassigns everyone, and the response is still a 200. Stated in
        // the spec's description for this route as well as here.
        String id = createTask("full");

        asAUser().body(Map.of("projectId", "proj_1", "title", MARKER + "stripped",
                        "status", "done"))
                .when().put("/api/tasks/" + id)
                .then()
                .statusCode(200)
                .body("status", is("done"))
                .body("description", is(nullValue()))
                .body("priority", is(nullValue()))
                .body("dueDate", is(nullValue()))
                .body("assignees", hasSize(0));

        // And it really is gone from the join table, not just from the response.
        Long remaining = pool.preparedQuery("SELECT count(*) FROM task_assignees WHERE task_id = $1")
                .execute(Tuple.of(id)).await().indefinitely().iterator().next().getLong(0);
        assertEquals(0L, remaining);
    }

    @Test
    void aTaskCanBeMovedToAnotherProject() {
        String id = createTask("moving");

        Map<String, Object> moved = task("moved");
        moved.put("projectId", "proj_2");

        asAUser().body(moved)
                .when().put("/api/tasks/" + id)
                .then().statusCode(200).body("projectId", is("proj_2"));

        asAUser().when().get("/api/tasks?projectId=proj_2")
                .then().body("id", hasItem(id));
    }

    @Test
    void theChangeSurvivesTheRequest() {
        String id = createTask("durable");
        asAUser().body(task("durable-edited"))
                .when().put("/api/tasks/" + id).then().statusCode(200);

        asAUser().when().get("/api/tasks")
                .then().body("find { it.id == '" + id + "' }.title", is(MARKER + "durable-edited"));
    }

    @Test
    void updatingSomethingThatDoesNotExistIsTheSpecsNotFound() {
        asAUser().body(task("ghost"))
                .when().put("/api/tasks/task_does_not_exist")
                .then()
                .statusCode(404)
                .contentType(ContentType.JSON)
                .body("success", is(false))
                .body("message", is(NOT_FOUND));
    }

    @Test
    void anInvalidBodyIsRejectedBeforeTheTaskIsLookedUp() {
        String id = createTask("unchanged");

        asAUser().body(Map.of("projectId", "proj_1", "title", ""))
                .when().put("/api/tasks/" + id)
                .then().statusCode(400).body("message", is(BAD_REQUEST));

        // Even for a task that does not exist: the body is judged first, so the answer is
        // 400 rather than 404.
        asAUser().body(Map.of("projectId", "proj_1", "title", ""))
                .when().put("/api/tasks/task_does_not_exist")
                .then().statusCode(400);

        asAUser().when().get("/api/tasks")
                .then().body("find { it.id == '" + id + "' }.title", is(MARKER + "unchanged"));
    }

    @Test
    void updatingNeedsAToken() {
        String id = createTask("protected");

        given().contentType(ContentType.JSON).body(task("hijacked"))
                .when().put("/api/tasks/" + id)
                .then().statusCode(401).body("message", is(UNAUTHORIZED));

        asAUser().when().get("/api/tasks")
                .then().body("find { it.id == '" + id + "' }.title", is(MARKER + "protected"));
    }

    // ---- 5.4 DELETE /api/tasks/{id} --------------------------------------------------

    @Test
    void deleteReturnsTheSpecsSuccessMessageAndRemovesTheTask() {
        String id = createTask("doomed");

        asAUser().when().delete("/api/tasks/" + id)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("success", is(true))
                .body("message", is("عملیات با موفقیت انجام شد."));

        asAUser().when().get("/api/tasks")
                .then().body("id", not(hasItem(id)));
    }

    @Test
    void deletingTwiceIsA404TheSecondTime() {
        String id = createTask("once");

        asAUser().when().delete("/api/tasks/" + id).then().statusCode(200);
        asAUser().when().delete("/api/tasks/" + id)
                .then().statusCode(404).body("message", is(NOT_FOUND));
    }

    @Test
    void deletingATaskTakesItsAssigneeRowsWithIt() {
        // Panache deletes by id with a bulk statement, which never visits the element
        // collection -- so this is the database's ON DELETE CASCADE doing the work, and
        // without it the rows would be orphaned or the delete would fail outright.
        String id = createTask("with-assignees");

        asAUser().when().delete("/api/tasks/" + id).then().statusCode(200);

        Long remaining = pool.preparedQuery("SELECT count(*) FROM task_assignees WHERE task_id = $1")
                .execute(Tuple.of(id)).await().indefinitely().iterator().next().getLong(0);
        assertEquals(0L, remaining);
    }

    @Test
    void deletingATaskLeavesItsProjectAlone() {
        String id = createTask("child");

        asAUser().when().delete("/api/tasks/" + id).then().statusCode(200);

        asAUser().when().get("/api/projects").then().body("id", hasItem("proj_1"));
    }

    @Test
    void deletingNeedsAToken() {
        String id = createTask("guarded");

        given().when().delete("/api/tasks/" + id)
                .then().statusCode(401).body("message", is(UNAUTHORIZED));

        asAUser().when().get("/api/tasks").then().body("id", hasItem(id));
    }

    // ---- the seeded fixture is not disturbed -----------------------------------------

    @Test
    void noneOfTheseRoutesTouchTheSeededTasksUnlessAskedTo() {
        createTask("noise");
        asAUser().when().get("/api/tasks")
                .then()
                .body("findAll { it.id ==~ /task_([1-9]|[12][0-9]|30)/ }", hasSize(30));
    }

    private static Map<String, Object> withAssignees(String title, List<String> assignees) {
        Map<String, Object> body = task(title);
        body.put("assignees", assignees);
        return body;
    }
}

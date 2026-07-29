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
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tasks 4.1 to 4.4: the four ordinary project routes. Sync is {@link ProjectSyncTest}.
 *
 * <p>Every project these tests create carries a title starting with the marker below, and
 * the cleanup deletes by that marker. The 30 seeded projects are read but never written:
 * `RepositoryTest` and `SpecSchemaCoverageTest` both depend on them being untouched.
 */
@QuarkusTest
class ProjectResourceTest {

    /** components/responses/NotFound, verbatim from the spec. */
    private static final String NOT_FOUND = "منبع مورد نظر پیدا نشد.";

    /** components/responses/BadRequest, verbatim from the spec. */
    private static final String BAD_REQUEST = "اطلاعات ورودی نامعتبر است.";

    /** components/responses/Unauthorized, verbatim from the spec. */
    private static final String UNAUTHORIZED = "دسترسی غیرمجاز. لطفا دوباره وارد شوید.";

    private static final String MARKER = "[test-4x] ";

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
        pool.preparedQuery("DELETE FROM projects WHERE title LIKE $1")
                .execute(Tuple.of(MARKER + "%")).await().indefinitely();
        pool.query("DELETE FROM refresh_tokens").execute().await().indefinitely();
    }

    private RequestSpecification asAUser() {
        return given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token);
    }

    private static Map<String, Object> project(String title) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", MARKER + title);
        body.put("description", "توضیح");
        body.put("color", "#3B82F6");
        body.put("icon", "FolderKanban");
        return body;
    }

    private String createProject(String title) {
        return asAUser().body(project(title))
                .when().post("/api/projects")
                .then().statusCode(201)
                .extract().path("id");
    }

    // ---- 4.1 GET /api/projects ------------------------------------------------------

    @Test
    void listsTheSeededProjectsWithEveryFieldOfTheSpecsSchema() {
        asAUser().when().get("/api/projects")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(30))
                .body("find { it.id == 'proj_1' }.title", is("طراحی سیستم مدیریت هوشمند"))
                .body("find { it.id == 'proj_1' }.description",
                        is("پروژه فاز اول سامانه ClickUp"))
                .body("find { it.id == 'proj_1' }.color", is("#3B82F6"))
                .body("find { it.id == 'proj_1' }.icon", is("FolderKanban"));
    }

    @Test
    void createdAtIsAnIsoInstantRatherThanAnEpochNumber() {
        // The spec types createdAt as a string with no format, so the format is a choice
        // this API makes -- and one a client parses. Pinned here so it cannot drift into
        // Jackson's numeric default.
        asAUser().when().get("/api/projects")
                .then()
                .statusCode(200)
                .body("createdAt", everyItem(matchesRegex("\\d{4}-\\d{2}-\\d{2}T.*Z")));
    }

    @Test
    void theListIsOldestFirst() {
        List<String> ids = asAUser().when().get("/api/projects")
                .then().statusCode(200).extract().jsonPath().getList("id");

        String newest = createProject("newest");
        List<String> after = asAUser().when().get("/api/projects")
                .then().statusCode(200).extract().jsonPath().getList("id");

        assertEquals(ids.size() + 1, after.size());
        assertEquals(newest, after.get(after.size() - 1), "a new project belongs at the end");
    }

    @Test
    void theListNeedsAToken() {
        given().when().get("/api/projects")
                .then().statusCode(401).body("message", is(UNAUTHORIZED));
    }

    // ---- 4.2 POST /api/projects -----------------------------------------------------

    @Test
    void createReturns201WithAServerAssignedIdAndTimestamp() {
        asAUser().body(project("created"))
                .when().post("/api/projects")
                .then()
                .statusCode(201)
                .contentType(ContentType.JSON)
                .body("id", matchesRegex("proj_\\d+"))
                .body("title", is(MARKER + "created"))
                .body("description", is("توضیح"))
                .body("color", is("#3B82F6"))
                .body("icon", is("FolderKanban"))
                .body("createdAt", matchesRegex("\\d{4}-\\d{2}-\\d{2}T.*Z"));
    }

    @Test
    void aCreatedProjectIsInTheListAfterwards() {
        String id = createProject("persisted");

        asAUser().when().get("/api/projects")
                .then().statusCode(200).body("id", hasItem(id));
    }

    @Test
    void onlyTitleIsRequiredAndTheRestComeBackNull() {
        asAUser().body(Map.of("title", MARKER + "bare"))
                .when().post("/api/projects")
                .then()
                .statusCode(201)
                .body("title", is(MARKER + "bare"))
                .body("description", is(nullValue()))
                .body("color", is(nullValue()))
                .body("icon", is(nullValue()));
    }

    @Test
    void aMissingOrBlankTitleIsTheSpecsBadRequest() {
        asAUser().body(Map.of("description", "بدون عنوان"))
                .when().post("/api/projects")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("message", is(BAD_REQUEST))
                .body("errors", contains("title: must not be blank"));

        asAUser().body(Map.of("title", "   "))
                .when().post("/api/projects")
                .then().statusCode(400).body("message", is(BAD_REQUEST));
    }

    @Test
    void valuesLongerThanTheirColumnAreRejectedRatherThanReachingPostgres() {
        Map<String, Object> tooLong = project("long");
        tooLong.put("title", MARKER + "ط".repeat(300));

        asAUser().body(tooLong)
                .when().post("/api/projects")
                .then().statusCode(400).body("message", is(BAD_REQUEST));
    }

    @Test
    void creatingNeedsAToken() {
        given().contentType(ContentType.JSON).body(project("unauthenticated"))
                .when().post("/api/projects")
                .then().statusCode(401).body("message", is(UNAUTHORIZED));

        asAUser().when().get("/api/projects")
                .then().body("title", not(hasItem(MARKER + "unauthenticated")));
    }

    // ---- 4.3 PUT /api/projects/{id} -------------------------------------------------

    @Test
    void updateReplacesTheContentsAndKeepsTheIdentity() {
        String id = createProject("before");
        String createdAt = asAUser().when().get("/api/projects")
                .then().extract().jsonPath().getString("find { it.id == '" + id + "' }.createdAt");

        Map<String, Object> replacement = project("after");
        replacement.put("color", "#EF4444");

        asAUser().body(replacement)
                .when().put("/api/projects/" + id)
                .then()
                .statusCode(200)
                .body("id", is(id))
                .body("title", is(MARKER + "after"))
                .body("color", is("#EF4444"))
                .body("createdAt", is(createdAt));
    }

    @Test
    void anOmittedFieldIsClearedBecauseThisIsAReplacement() {
        // The decision recorded in task.md: PUT takes CreateProjectRequest, the same
        // schema POST uses, so absent means null on both. PUT /api/users/me merges
        // instead -- different schema, different rule, and a test on each side saying so.
        String id = createProject("full");

        asAUser().body(Map.of("title", MARKER + "stripped"))
                .when().put("/api/projects/" + id)
                .then()
                .statusCode(200)
                .body("title", is(MARKER + "stripped"))
                .body("description", is(nullValue()))
                .body("color", is(nullValue()))
                .body("icon", is(nullValue()));
    }

    @Test
    void theChangeSurvivesTheRequest() {
        String id = createProject("durable");
        asAUser().body(project("durable-edited"))
                .when().put("/api/projects/" + id).then().statusCode(200);

        asAUser().when().get("/api/projects")
                .then().body("find { it.id == '" + id + "' }.title", is(MARKER + "durable-edited"));
    }

    @Test
    void updatingSomethingThatDoesNotExistIsTheSpecsNotFound() {
        asAUser().body(project("ghost"))
                .when().put("/api/projects/proj_does_not_exist")
                .then()
                .statusCode(404)
                .contentType(ContentType.JSON)
                .body("success", is(false))
                .body("message", is(NOT_FOUND));
    }

    @Test
    void anInvalidBodyIsRejectedBeforeTheProjectIsLookedUp() {
        String id = createProject("unchanged");

        asAUser().body(Map.of("title", ""))
                .when().put("/api/projects/" + id)
                .then().statusCode(400).body("message", is(BAD_REQUEST));

        asAUser().when().get("/api/projects")
                .then().body("find { it.id == '" + id + "' }.title", is(MARKER + "unchanged"));
    }

    // ---- 4.4 DELETE /api/projects/{id} ----------------------------------------------

    @Test
    void deleteReturnsTheSpecsSuccessMessageAndRemovesTheProject() {
        String id = createProject("doomed");

        asAUser().when().delete("/api/projects/" + id)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("success", is(true))
                .body("message", is("عملیات با موفقیت انجام شد."));

        asAUser().when().get("/api/projects")
                .then().body("id", not(hasItem(id)));
    }

    @Test
    void deletingTwiceIsA404TheSecondTime() {
        String id = createProject("once");

        asAUser().when().delete("/api/projects/" + id).then().statusCode(200);
        asAUser().when().delete("/api/projects/" + id)
                .then().statusCode(404).body("message", is(NOT_FOUND));
    }

    @Test
    void deletingAProjectTakesItsTasksWithIt() {
        String id = createProject("with-tasks");
        pool.preparedQuery("INSERT INTO tasks (id, title, status, project_id) "
                        + "VALUES ($1, $2, 'todo', $3)")
                .execute(Tuple.of("task_test_4x", MARKER + "task", id)).await().indefinitely();

        asAUser().when().delete("/api/projects/" + id).then().statusCode(200);

        // ON DELETE CASCADE, recorded in changelog 001 as a deliberate departure. Without
        // it the delete would fail on the foreign key instead, since project_id is NOT NULL.
        Long remaining = pool.preparedQuery("SELECT count(*) FROM tasks WHERE id = $1")
                .execute(Tuple.of("task_test_4x")).await().indefinitely()
                .iterator().next().getLong(0);
        assertEquals(0L, remaining);
    }

    @Test
    void deletingNeedsAToken() {
        String id = createProject("protected");

        given().when().delete("/api/projects/" + id)
                .then().statusCode(401).body("message", is(UNAUTHORIZED));

        asAUser().when().get("/api/projects").then().body("id", hasItem(id));
    }

    // ---- the seeded fixture is not disturbed ----------------------------------------

    @Test
    void noneOfTheseRoutesTouchTheSeededProjectsUnlessAskedTo() {
        createProject("noise");
        asAUser().when().get("/api/projects")
                .then()
                .body("findAll { it.id ==~ /proj_([1-9]|[12][0-9]|30)/ }", hasSize(30));
    }
}

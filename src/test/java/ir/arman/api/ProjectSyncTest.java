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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 4.5: POST /api/projects/sync, under decision D5 -- upsert by id, delete nothing.
 *
 * <p>The tests that matter most here are the ones asserting what does <em>not</em>
 * happen: an unlisted project is still there afterwards, and syncing the same payload
 * twice does not duplicate anything.
 */
@QuarkusTest
class ProjectSyncTest {

    private static final String BAD_REQUEST = "اطلاعات ورودی نامعتبر است.";
    private static final String UNAUTHORIZED = "دسترسی غیرمجاز. لطفا دوباره وارد شوید.";

    private static final String MARKER = "[test-45] ";

    /** Deliberately far above anything the sequence has reached. */
    private static final String FAR_FUTURE_ID = "proj_900000";

    @Inject
    Pool pool;

    private String token;
    private long sequenceBefore;

    @BeforeEach
    void signIn() {
        token = given().contentType(ContentType.JSON)
                .body(Map.of("studentId", "99100111", "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");
        sequenceBefore = sequenceValue();
    }

    @AfterEach
    void removeWhatTheseTestsMade() {
        pool.preparedQuery("DELETE FROM projects WHERE title LIKE $1")
                .execute(Tuple.of(MARKER + "%")).await().indefinitely();
        pool.query("DELETE FROM refresh_tokens").execute().await().indefinitely();
        // Put the sequence back: one of these tests pushes it deliberately high, and
        // leaving it there would hand every later test absurd ids.
        pool.preparedQuery("SELECT setval('projects_id_seq', $1, TRUE)")
                .execute(Tuple.of(sequenceBefore)).await().indefinitely();
    }

    private long sequenceValue() {
        return pool.query("SELECT last_value FROM projects_id_seq").execute()
                .await().indefinitely().iterator().next().getLong(0);
    }

    private RequestSpecification asAUser() {
        return given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token);
    }

    private static Map<String, Object> element(String id, String title) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", id);
        body.put("title", MARKER + title);
        body.put("description", "توضیح");
        body.put("color", "#10B981");
        body.put("icon", "Database");
        return body;
    }

    private void sync(Object... elements) {
        asAUser().body(Map.of("projects", List.of(elements)))
                .when().post("/api/projects/sync")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("message", is("عملیات با موفقیت انجام شد."));
    }

    private String titleOf(String id) {
        return asAUser().when().get("/api/projects")
                .then().statusCode(200)
                .extract().jsonPath().getString("find { it.id == '" + id + "' }.title");
    }

    private int projectCount() {
        return asAUser().when().get("/api/projects")
                .then().statusCode(200).extract().jsonPath().getList("id").size();
    }

    // ---- what sync does --------------------------------------------------------------

    @Test
    void anUnknownIdIsCreatedUnderTheIdTheClientSent() {
        sync(element("proj_offline_1", "made offline"));

        assertEquals(MARKER + "made offline", titleOf("proj_offline_1"));
    }

    @Test
    void aKnownIdIsUpdatedInPlace() {
        sync(element("proj_offline_2", "first"));
        int after = projectCount();

        sync(element("proj_offline_2", "second"));

        assertEquals(MARKER + "second", titleOf("proj_offline_2"));
        assertEquals(after, projectCount(), "the second sync must not have added a row");
    }

    @Test
    void syncingTheSamePayloadTwiceChangesNothingTheSecondTime() {
        // Idempotence is the whole reason the client's id is honoured rather than a fresh
        // one generated: otherwise every sync would duplicate the same offline project.
        Map<String, Object> payload = element("proj_offline_3", "stable");

        sync(payload);
        int after = projectCount();
        String first = titleOf("proj_offline_3");

        sync(payload);

        assertEquals(after, projectCount());
        assertEquals(first, titleOf("proj_offline_3"));
    }

    @Test
    void severalElementsAreAppliedInOneRequest() {
        sync(element("proj_offline_4", "a"),
                element("proj_offline_5", "b"),
                element("proj_offline_6", "c"));

        assertEquals(MARKER + "a", titleOf("proj_offline_4"));
        assertEquals(MARKER + "b", titleOf("proj_offline_5"));
        assertEquals(MARKER + "c", titleOf("proj_offline_6"));
    }

    @Test
    void anUpdateThroughSyncReplacesRatherThanMerges() {
        sync(element("proj_offline_7", "full"));

        Map<String, Object> sparse = new HashMap<>();
        sparse.put("id", "proj_offline_7");
        sparse.put("title", MARKER + "sparse");
        sync(sparse);

        asAUser().when().get("/api/projects")
                .then()
                .body("find { it.id == 'proj_offline_7' }.description", is(nullValue()))
                .body("find { it.id == 'proj_offline_7' }.color", is(nullValue()));
    }

    // ---- what sync does not do -------------------------------------------------------

    @Test
    void aProjectMissingFromThePayloadIsLeftAlone() {
        // The heart of D5. Under the other reading of "bulk sync" this request would
        // delete all 30 seeded projects and, through the cascade, every task on them.
        sync(element("proj_offline_8", "only one"));

        asAUser().when().get("/api/projects")
                .then()
                .body("id", hasItem("proj_1"))
                .body("id", hasItem("proj_30"))
                .body("find { it.id == 'proj_1' }.title", is("طراحی سیستم مدیریت هوشمند"));
    }

    @Test
    void anEmptyArrayIsAValidRequestThatDeletesNothing() {
        int before = projectCount();

        asAUser().body(Map.of("projects", List.of()))
                .when().post("/api/projects/sync")
                .then().statusCode(200).body("success", is(true));

        assertEquals(before, projectCount());
    }

    @Test
    void aClientSuppliedCreatedAtIsIgnoredAndTheServerStampsItsOwn() {
        Map<String, Object> backdated = element("proj_offline_9", "backdated");
        backdated.put("createdAt", "1999-01-01T00:00:00Z");
        sync(backdated);

        String stamped = asAUser().when().get("/api/projects")
                .then().extract().jsonPath()
                .getString("find { it.id == 'proj_offline_9' }.createdAt");

        assertTrue(stamped.startsWith("20"), "expected a server timestamp, got " + stamped);
        assertTrue(!stamped.startsWith("1999"), "the client's timestamp was honoured");
    }

    // ---- bad requests ----------------------------------------------------------------

    @Test
    void aDuplicateIdInOnePayloadIsRejectedRatherThanResolvedByOrder() {
        asAUser().body(Map.of("projects", List.of(
                        element("proj_offline_10", "first"),
                        element("proj_offline_10", "second"))))
                .when().post("/api/projects/sync")
                .then()
                .statusCode(400)
                .body("message", is(BAD_REQUEST))
                .body("errors", hasItem("projects: duplicate id proj_offline_10"));

        // And nothing was written: the check runs before any of it is applied.
        asAUser().when().get("/api/projects").then().body("id", not(hasItem("proj_offline_10")));
    }

    @Test
    void anElementWithoutAnIdIsRejectedBecauseThereIsNothingToUpsert() {
        Map<String, Object> noId = new HashMap<>();
        noId.put("title", MARKER + "no id");

        asAUser().body(Map.of("projects", List.of(noId)))
                .when().post("/api/projects/sync")
                .then().statusCode(400).body("message", is(BAD_REQUEST));
    }

    @Test
    void aMissingProjectsArrayIsTheSpecsBadRequest() {
        asAUser().body(Map.of())
                .when().post("/api/projects/sync")
                .then().statusCode(400).body("message", is(BAD_REQUEST));
    }

    @Test
    void syncNeedsAToken() {
        given().contentType(ContentType.JSON)
                .body(Map.of("projects", List.of(element("proj_offline_11", "unauthenticated"))))
                .when().post("/api/projects/sync")
                .then().statusCode(401).body("message", is(UNAUTHORIZED));
    }

    // ---- the sequence cannot be poisoned ---------------------------------------------

    @Test
    void aClientIdFromTheFutureDoesNotBreakTheNextOrdinaryCreate() {
        // Without the sequence being pushed forward, POST /api/projects would keep
        // handing out proj_44, proj_45 ... and eventually collide with this row as a
        // primary key violation -- a 500 from an endpoint that did nothing wrong.
        sync(element(FAR_FUTURE_ID, "from the future"));

        assertTrue(sequenceValue() >= 900000L,
                "the sequence should have been pushed past the client's id");

        String next = asAUser().body(Map.of("title", MARKER + "after the future"))
                .when().post("/api/projects")
                .then().statusCode(201)
                .extract().path("id");

        assertTrue(Long.parseLong(next.substring("proj_".length())) > 900000L,
                "expected an id beyond the client's, got " + next);
    }

    @Test
    void anIdThatIsNotOfTheGeneratedShapeLeavesTheSequenceAlone() {
        long before = sequenceValue();
        sync(element("local-uuid-7f3a", "not a proj_ id"));

        assertEquals(before, sequenceValue());
        assertEquals(MARKER + "not a proj_ id", titleOf("local-uuid-7f3a"));
    }

    @Test
    void ordinaryCreatesStillGetSequentialIdsWhenNoSyncHasRun() {
        String first = asAUser().body(Map.of("title", MARKER + "seq a"))
                .when().post("/api/projects").then().statusCode(201).extract().path("id");
        String second = asAUser().body(Map.of("title", MARKER + "seq b"))
                .when().post("/api/projects").then().statusCode(201).extract().path("id");

        assertTrue(first.matches("proj_\\d+") && second.matches("proj_\\d+"));
        assertTrue(Long.parseLong(second.substring(5)) > Long.parseLong(first.substring(5)));
    }

    @Test
    void syncedProjectsAppearInTheOrdinaryListing() {
        sync(element("proj_offline_12", "listed"));

        asAUser().when().get("/api/projects")
                .then()
                .statusCode(200)
                .body("id", hasItem("proj_offline_12"))
                .body("find { it.id == 'proj_offline_12' }.createdAt",
                        matchesRegex("\\d{4}-\\d{2}-\\d{2}T.*Z"));
    }
}

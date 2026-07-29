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
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * Task 12.3: Persian text through the full HTTP -&gt; DB -&gt; HTTP path, on every resource
 * that stores free text.
 *
 * <p>{@link ChatResourceTest#persianContentSurvivesTheRoundTrip()} already checks a POST
 * against the row Postgres actually stored, straight over the injected pool. That proves
 * the write side. This class proves the read side too, on resources that class does not
 * touch: it posts, then asks the same content back through the ordinary GET routes rather
 * than through SQL, so what is pinned is the property Quarkus test doubles cannot fake --
 * the JSON that went in over HTTP is byte-for-byte the JSON that comes back out, after a
 * real encode, a real column, and a real decode in between.
 *
 * <p>One string, reused everywhere: right-to-left Persian letters, a half-space
 * (nim-fasele, U+200C) inside a real word, Eastern Arabic-Indic digits, and Persian
 * quotation marks. Each of those has broken something in this codebase's history at least
 * once -- ASCII-only test strings would not have caught any of it.
 */
@QuarkusTest
class PersianRoundTripTest {

    private static final String MARKER = "[test-123] ";

    /** نیم‌فاصله (U+200C) inside «می‌خواهم», Eastern Arabic-Indic digits, Persian quotes. */
    private static final String PERSIAN = "متن «فارسی» با نیم‌فاصله در می‌خواهم و رقم ۱۲۳۴۵";

    private static final String THROWAWAY_PREFIX = "test-123-";

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
        pool.preparedQuery("DELETE FROM projects WHERE title LIKE $1")
                .execute(Tuple.of(MARKER + "%")).await().indefinitely();
        pool.preparedQuery("DELETE FROM weekly_reports WHERE week_title LIKE $1")
                .execute(Tuple.of(MARKER + "%")).await().indefinitely();
        pool.preparedQuery("DELETE FROM refresh_tokens WHERE user_id IN "
                        + "(SELECT id FROM users WHERE student_id LIKE $1)")
                .execute(Tuple.of(THROWAWAY_PREFIX + "%")).await().indefinitely();
        pool.preparedQuery("DELETE FROM users WHERE student_id LIKE $1")
                .execute(Tuple.of(THROWAWAY_PREFIX + "%")).await().indefinitely();
        pool.query("DELETE FROM refresh_tokens").execute().await().indefinitely();
    }

    private RequestSpecification asAUser() {
        return given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token);
    }

    @Test
    void aProjectsTitleAndDescriptionSurviveAPostThenAGet() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", MARKER + PERSIAN);
        body.put("description", PERSIAN);

        String id = asAUser().body(body)
                .when().post("/api/projects")
                .then().statusCode(201).extract().path("id");

        asAUser().when().get("/api/projects")
                .then().statusCode(200)
                .body("find { it.id == '" + id + "' }.title", is(MARKER + PERSIAN))
                .body("find { it.id == '" + id + "' }.description", is(PERSIAN));
    }

    @Test
    void aTasksTitleAndDescriptionSurviveAPostThenAGet() {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", "proj_1");
        body.put("title", MARKER + PERSIAN);
        body.put("description", PERSIAN);
        body.put("status", "todo");

        String id = asAUser().body(body)
                .when().post("/api/tasks")
                .then().statusCode(201).extract().path("id");

        asAUser().when().get("/api/tasks?projectId=proj_1")
                .then().statusCode(200)
                .body("find { it.id == '" + id + "' }.title", is(MARKER + PERSIAN))
                .body("find { it.id == '" + id + "' }.description", is(PERSIAN));
    }

    @Test
    void aReportsFreeTextFieldsSurviveAPostThenAGet() {
        Map<String, Object> body = new HashMap<>();
        body.put("weekTitle", MARKER + PERSIAN);
        body.put("hoursWorked", 10);
        body.put("tasksCompleted", 1);
        body.put("achievements", PERSIAN);
        body.put("challenges", PERSIAN);
        body.put("nextWeekPlan", PERSIAN);

        String id = asAUser().body(body)
                .when().post("/api/reports")
                .then().statusCode(201).extract().path("id");

        asAUser().when().get("/api/reports")
                .then().statusCode(200)
                .body("find { it.id == '" + id + "' }.weekTitle", is(MARKER + PERSIAN))
                .body("find { it.id == '" + id + "' }.achievements", is(PERSIAN))
                .body("find { it.id == '" + id + "' }.challenges", is(PERSIAN))
                .body("find { it.id == '" + id + "' }.nextWeekPlan", is(PERSIAN));
    }

    @Test
    void aProfileNameSurvivesAPutThenAGet() {
        // A throwaway account: renaming usr_101 would leak into every other test in this
        // suite that reads the seeded name "علی محمدی".
        String studentId = THROWAWAY_PREFIX + UUID.randomUUID();
        String freshToken = given().contentType(ContentType.JSON)
                .body(Map.of("name", "نام اولیه", "studentId", studentId,
                        "password", "Password123"))
                .when().post("/api/auth/register")
                .then().statusCode(201).extract().path("accessToken");

        given().contentType(ContentType.JSON).header("Authorization", "Bearer " + freshToken)
                .body(Map.of("name", PERSIAN))
                .when().put("/api/users/me")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + freshToken)
                .when().get("/api/users/me")
                .then().statusCode(200).body("name", is(PERSIAN));
    }

    @Test
    void aMembersNameSurvivesRegistrationThenTheDirectoryListing() {
        // TeamMember carries no studentId -- phase 7 rejected that deliberately, so the
        // account can only be found again in the directory by the name it registered
        // under, not by the id it registered with.
        String studentId = THROWAWAY_PREFIX + UUID.randomUUID();
        given().contentType(ContentType.JSON)
                .body(Map.of("name", PERSIAN, "studentId", studentId, "password", "Password123"))
                .when().post("/api/auth/register")
                .then().statusCode(201);

        asAUser().when().get("/api/members")
                .then().statusCode(200)
                .body("findAll { it.name == '" + PERSIAN + "' }.size()", is(1));
    }
}

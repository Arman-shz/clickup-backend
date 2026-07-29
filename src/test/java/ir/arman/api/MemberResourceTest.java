package ir.arman.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isOneOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task 7.1: GET /api/members -- the team directory, read-only.
 *
 * <p>Most of what is asserted here is what the response does <em>not</em> carry. The
 * route reads the same rows /api/users/me does, and the TeamMember schema is four
 * properties narrower; a directory that leaked studentId would be handing out half of
 * everyone's credentials.
 */
@QuarkusTest
class MemberResourceTest {

    /** components/responses/Unauthorized, verbatim from the spec. */
    private static final String UNAUTHORIZED = "دسترسی غیرمجاز. لطفا دوباره وارد شوید.";

    private static final String THROWAWAY_PREFIX = "test-member-";

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
        pool.preparedQuery("DELETE FROM refresh_tokens WHERE user_id IN "
                        + "(SELECT id FROM users WHERE student_id LIKE $1)")
                .execute(Tuple.of(THROWAWAY_PREFIX + "%")).await().indefinitely();
        pool.preparedQuery("DELETE FROM users WHERE student_id LIKE $1")
                .execute(Tuple.of(THROWAWAY_PREFIX + "%")).await().indefinitely();
        pool.query("DELETE FROM refresh_tokens").execute().await().indefinitely();
    }

    private io.restassured.specification.RequestSpecification asAUser() {
        return given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token);
    }

    @Test
    void listsEveryAccountWithTheSpecsFiveProperties() {
        asAUser().when().get("/api/members")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", is(30))
                .body("find { it.id == 'usr_101' }.name", is("علی محمدی"))
                .body("find { it.id == 'usr_101' }.role", is("student"))
                .body("find { it.id == 'usr_101' }.status", is("active"))
                .body("find { it.id == 'usr_102' }.role", is("admin"));
    }

    @Test
    void theDirectoryCarriesNothingBeyondTheTeamMemberSchema() {
        // studentId is the login identifier and theme/language/notificationsEnabled are
        // the caller's own settings. None of them belongs in a list of colleagues.
        List<Map<String, Object>> members = asAUser().when().get("/api/members")
                .then().statusCode(200).extract().jsonPath().getList("$");

        for (Map<String, Object> member : members) {
            assertEquals(Set.copyOf(List.of("id", "name", "email", "role", "status")),
                    member.keySet(), "unexpected properties on " + member.get("id"));
        }
    }

    @Test
    void theListIsOrderedByNameInPersianCollation() {
        // Ordered by the database with the ICU `fa` locale rather than in Java, so the
        // ordering is the one a Persian reader expects rather than by code point.
        List<String> fromTheApi = asAUser().when().get("/api/members")
                .then().statusCode(200).extract().jsonPath().getList("name");

        List<String> fromTheDatabase = pool.query("SELECT name FROM users ORDER BY name")
                .execute().await().indefinitely()
                .stream().map(row -> row.getString(0)).toList();

        assertEquals(fromTheDatabase, fromTheApi);
    }

    @Test
    void inactiveMembersAreListedRatherThanHidden() {
        // status is in the schema, which is only worth sending if the caller is meant to
        // see members who are not active and render them differently.
        asAUser().when().get("/api/members")
                .then()
                .body("status", hasItem("inactive"))
                .body("status", everyItem(isOneOf("active", "inactive")));
    }

    @Test
    void everyRoleIsOneTheSpecDocuments() {
        asAUser().when().get("/api/members")
                .then().body("role", everyItem(isOneOf("admin", "student")));
    }

    @Test
    void anAccountWithNoEmailIsListedWithANullOne() {
        // Registration collects a name, a student id and a password only, so an account
        // exists before it has an address -- and the directory must still show it.
        String studentId = THROWAWAY_PREFIX + UUID.randomUUID();
        given().contentType(ContentType.JSON)
                .body(Map.of("name", "عضو بی‌ایمیل", "studentId", studentId,
                        "password", "Password123"))
                .when().post("/api/auth/register").then().statusCode(201);

        asAUser().when().get("/api/members")
                .then()
                .statusCode(200)
                .body("size()", is(31))
                .body("find { it.name == 'عضو بی‌ایمیل' }.email", is(nullValue()))
                .body("find { it.name == 'عضو بی‌ایمیل' }.role", is("student"))
                .body("find { it.name == 'عضو بی‌ایمیل' }.status", is("active"));
    }

    @Test
    void aNewlyRegisteredAccountAppearsWithoutBeingAdded() {
        // There is no POST on this path in the spec, and none is invented here: members
        // arrive by registering themselves.
        int before = asAUser().when().get("/api/members")
                .then().extract().jsonPath().getList("id").size();

        given().contentType(ContentType.JSON)
                .body(Map.of("name", "تازه‌وارد", "studentId",
                        THROWAWAY_PREFIX + UUID.randomUUID(), "password", "Password123"))
                .when().post("/api/auth/register").then().statusCode(201);

        asAUser().when().get("/api/members")
                .then().body("size()", is(before + 1));
    }

    @Test
    void theDirectoryIsReadOnly() {
        // No write verb on /api/members exists in the document. Asserted so that adding
        // one later is a deliberate change to the contract rather than a quiet one.
        asAUser().body(Map.of("name", "نفوذی")).when().post("/api/members")
                .then().statusCode(405);
        // No /api/members/{id} path exists at all, so that one is a 404 rather than a 405.
        asAUser().when().delete("/api/members/usr_101").then().statusCode(404);
    }

    @Test
    void emailIsPresentForTheSeededAccountsThatHaveOne() {
        asAUser().when().get("/api/members")
                .then().body("find { it.id == 'usr_101' }.email", is(notNullValue()));
    }

    @Test
    void theDirectoryNeedsAToken() {
        given().when().get("/api/members")
                .then().statusCode(401).body("message", is(UNAUTHORIZED));
    }

    @Test
    void readingTheDirectoryChangesNothing() {
        asAUser().when().get("/api/members").then().statusCode(200);

        Long accounts = pool.query("SELECT count(*) FROM users").execute()
                .await().indefinitely().iterator().next().getLong(0);
        assertEquals(30L, accounts);
    }

    @Test
    void everyMemberHasTheThreeRequiredProperties() {
        asAUser().when().get("/api/members")
                .then()
                .body("id", everyItem(notNullValue()))
                .body("name", everyItem(notNullValue()))
                .body("findAll { it.id == null }", hasSize(0));
    }
}

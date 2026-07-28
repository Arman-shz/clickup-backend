package ir.arman.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.smallrye.jwt.build.Jwt;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task 3.1: GET /api/users/me. */
@QuarkusTest
class UserProfileTest {

    /** components/responses/Unauthorized, verbatim from the spec. */
    private static final String UNAUTHORIZED = "دسترسی غیرمجاز. لطفا دوباره وارد شوید.";

    @Inject
    Pool pool;

    @AfterEach
    void discardTokensMintedByTheseTests() {
        pool.query("DELETE FROM refresh_tokens").execute().await().indefinitely();
    }

    private String accessTokenFor(String studentId) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("studentId", studentId, "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");
    }

    @Test
    void returnsEveryFieldOfTheSpecsUserProfileSchema() {
        // usr_101 is the seeded account the spec's own login example uses.
        given().header("Authorization", "Bearer " + accessTokenFor("99100111"))
                .when().get("/api/users/me")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("id", is("usr_101"))
                .body("studentId", is("99100111"))
                .body("name", is("علی محمدی"))
                .body("email", is("a.mohammadi@example.com"))
                .body("role", is("student"))
                .body("avatar", is("https://cdn.example.com/avatars/user101.jpg"))
                .body("theme", is("light"))
                .body("language", is("fa"))
                .body("notificationsEnabled", is(true));
    }

    @Test
    void neverExposesThePasswordHashOrTheAccountStatus() {
        // The response is built from a DTO precisely so that these cannot appear. Asserted
        // rather than assumed: adding a field to the entity must not add it here.
        given().header("Authorization", "Bearer " + accessTokenFor("99100111"))
                .when().get("/api/users/me")
                .then()
                .statusCode(200)
                .body("passwordHash", is(nullValue()))
                .body("password", is(nullValue()))
                .body("status", is(nullValue()))
                .body("createdAt", is(nullValue()));
    }

    @Test
    void theSubjectIsTheTokenHolderAndNotAnythingTheCallerCanAskFor() {
        // Two different tokens, two different profiles, same URL: the route takes no id.
        given().header("Authorization", "Bearer " + accessTokenFor("99100112"))
                .when().get("/api/users/me")
                .then().statusCode(200).body("id", is("usr_102")).body("role", is("admin"));

        given().header("Authorization", "Bearer " + accessTokenFor("99100111"))
                .when().get("/api/users/me")
                .then().statusCode(200).body("id", is("usr_101")).body("role", is("student"));
    }

    @Test
    void anAdminSeesTheirOwnProfileAndNotAPrivilegedView() {
        // The route is @Authenticated, not @RolesAllowed: both roles reach it, and role
        // changes nothing about what comes back.
        given().header("Authorization", "Bearer " + accessTokenFor("99100112"))
                .when().get("/api/users/me")
                .then().statusCode(200).body("studentId", is("99100112"));
    }

    @Test
    void withoutATokenTheSpecsUnauthorizedBodyComesBack() {
        given().when().get("/api/users/me")
                .then()
                .statusCode(401)
                .contentType(ContentType.JSON)
                .body("success", is(false))
                .body("message", is(UNAUTHORIZED));
    }

    @Test
    void anExpiredTokenIsRefused() {
        String expired = Jwt.upn("usr_101").subject("usr_101").groups("student")
                .issuer("https://arman.ir/clickup")
                .expiresAt(Instant.now().minus(Duration.ofMinutes(5)))
                .sign();

        given().header("Authorization", "Bearer " + expired)
                .when().get("/api/users/me")
                .then().statusCode(401).body("message", is(UNAUTHORIZED));
    }

    @Test
    void aWellFormedTokenNamingNoAccountIsRefusedRatherThanCrashing() {
        // Signed with the application's own key, so it authenticates -- but usr_999 does
        // not exist. The spec documents no 404 here, and a 500 would be a bug.
        String orphan = Jwt.upn("usr_999").subject("usr_999").groups("student")
                .issuer("https://arman.ir/clickup")
                .expiresIn(Duration.ofMinutes(15))
                .sign();

        given().header("Authorization", "Bearer " + orphan)
                .when().get("/api/users/me")
                .then()
                .statusCode(401)
                .body("message", is(UNAUTHORIZED));
    }

    @Test
    void theProfileMatchesTheOneLoginHandedBack() {
        // LoginResponse.user and GET /api/users/me are both UserProfile. A client that
        // trusts the login payload and one that re-reads must see the same thing.
        Response login = given().contentType(ContentType.JSON)
                .body(Map.of("studentId", "99100111", "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200).extract().response();

        String token = login.path("accessToken");
        Response profile = given().header("Authorization", "Bearer " + token)
                .when().get("/api/users/me")
                .then().statusCode(200).extract().response();

        assertEquals(login.jsonPath().getMap("user"), profile.jsonPath().getMap(""));
    }

    @Test
    void aRefreshTokenDoesNotOpenTheProfile() {
        String refreshToken = given().contentType(ContentType.JSON)
                .body(Map.of("studentId", "99100111", "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200).extract().path("refreshToken");

        given().header("Authorization", "Bearer " + refreshToken)
                .when().get("/api/users/me")
                .then().statusCode(401).body("message", is(UNAUTHORIZED));
    }
}

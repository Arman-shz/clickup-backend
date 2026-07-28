package ir.arman.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
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

/**
 * Tasks 2.5 and 2.6: the BearerAuth scheme the spec declares, and the body a rejected
 * request gets back. Exercised through {@link ProtectedProbeResource}, which exists only
 * on the test classpath -- see its javadoc for why.
 */
@QuarkusTest
class BearerAuthTest {

    /** components/responses/Unauthorized, verbatim from the spec. */
    private static final String UNAUTHORIZED = "دسترسی غیرمجاز. لطفا دوباره وارد شوید.";

    @Inject
    Pool pool;

    @AfterEach
    void discardTokensMintedByTheseTests() {
        pool.query("DELETE FROM refresh_tokens").execute().await().indefinitely();
    }

    /** A real access token, obtained the way a client would. */
    private String accessTokenFor(String studentId) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("studentId", studentId, "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");
    }

    // ---- 2.5 the token is accepted, and the role travels with it ------------------

    @Test
    void aTokenFromLoginIsAcceptedAndIdentifiesTheAccount() {
        given().header("Authorization", "Bearer " + accessTokenFor("99100111"))
                .when().get("/test-only/protected/any")
                .then()
                .statusCode(200)
                // upn, which TokenService sets to the user id.
                .body(is("usr_101"));
    }

    @Test
    void theRoleInTheTokenIsTheRoleRolesAllowedSees() {
        String student = accessTokenFor("99100111");
        String admin = accessTokenFor("99100112");

        given().header("Authorization", "Bearer " + student)
                .when().get("/test-only/protected/student")
                .then().statusCode(200).body(is("student: usr_101"));

        given().header("Authorization", "Bearer " + admin)
                .when().get("/test-only/protected/admin")
                .then().statusCode(200).body(is("admin: usr_102"));
    }

    @Test
    void aStudentIsRefusedAnAdminOnlyRouteWithForbiddenNotUnauthorized() {
        // 403, not 401: the caller is authenticated, they are simply not allowed. The
        // spec documents no admin-only route, so nothing in the API can reach this
        // today -- it is here so that whatever adds one inherits the right status.
        given().header("Authorization", "Bearer " + accessTokenFor("99100111"))
                .when().get("/test-only/protected/admin")
                .then().statusCode(403);
    }

    @Test
    void anAdminIsNotAutomaticallyAStudent() {
        // Roles are not hierarchical here; `admin` does not imply `student`.
        given().header("Authorization", "Bearer " + accessTokenFor("99100112"))
                .when().get("/test-only/protected/student")
                .then().statusCode(403);
    }

    // ---- 2.6 what a rejected request gets back ------------------------------------

    @Test
    void noTokenAtAllIsRefusedWithTheSpecsUnauthorizedBody() {
        given().when().get("/test-only/protected/any")
                .then()
                .statusCode(401)
                .body("success", is(false))
                .body("message", is(UNAUTHORIZED));
    }

    @Test
    void aTokenThatIsNotEvenAJwtIsRefusedWithTheSameBody() {
        given().header("Authorization", "Bearer not-a-token")
                .when().get("/test-only/protected/any")
                .then()
                .statusCode(401)
                .body("success", is(false))
                .body("message", is(UNAUTHORIZED));
    }

    @Test
    void anExpiredTokenIsRefused() {
        // Signed with the same key the application verifies with, so only the expiry
        // can be what rejects it.
        String expired = Jwt.upn("usr_101")
                .subject("usr_101")
                .groups("student")
                .issuer("https://arman.ir/clickup")
                .expiresAt(Instant.now().minus(Duration.ofMinutes(5)))
                .sign();

        given().header("Authorization", "Bearer " + expired)
                .when().get("/test-only/protected/any")
                .then().statusCode(401).body("message", is(UNAUTHORIZED));
    }

    @Test
    void aTokenFromTheWrongIssuerIsRefused() {
        String wrongIssuer = Jwt.upn("usr_101")
                .subject("usr_101")
                .groups("student")
                .issuer("https://somewhere-else.example/")
                .expiresIn(Duration.ofMinutes(15))
                .sign();

        given().header("Authorization", "Bearer " + wrongIssuer)
                .when().get("/test-only/protected/any")
                .then().statusCode(401).body("message", is(UNAUTHORIZED));
    }

    @Test
    void aRefreshTokenIsNotAnAccessToken() {
        String refreshToken = given().contentType(ContentType.JSON)
                .body(Map.of("studentId", "99100111", "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("refreshToken");

        // An opaque UUID carries no signature and must not open anything by itself.
        given().header("Authorization", "Bearer " + refreshToken)
                .when().get("/test-only/protected/any")
                .then().statusCode(401);
    }

    // ---- the auth routes themselves stay open ------------------------------------

    @Test
    void unannotatedRoutesRemainReachableWithoutAToken() {
        given().when().get("/test-only/protected/open").then().statusCode(200);
    }

    @Test
    void loginAndRegisterAndRefreshDoNotRequireAToken() {
        // The obvious deadlock: needing a token to obtain a token.
        given().contentType(ContentType.JSON)
                .body(Map.of("studentId", "99100111", "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200);

        given().contentType(ContentType.JSON)
                .body(Map.of("refreshToken", "not-a-uuid"))
                .when().post("/api/auth/refresh")
                .then().statusCode(401)
                // 401 because the token is unusable, not because authentication was
                // demanded: an unauthenticated caller reached the endpoint's own logic.
                .body("message", is(UNAUTHORIZED));

        given().contentType(ContentType.JSON)
                .body(Map.of("name", "x", "studentId", "test-bearer-001", "password", "short"))
                .when().post("/api/auth/register")
                .then().statusCode(400);
    }

    @Test
    void httpBasicIsNotAWayIn() {
        // security-jpa would otherwise let Quarkus offer Basic. quarkus.http.auth.basic
        // is false, so credentials in a Basic header must buy nothing.
        given().auth().preemptive().basic("99100111", "Password123")
                .when().get("/test-only/protected/any")
                .then().statusCode(401);
    }
}

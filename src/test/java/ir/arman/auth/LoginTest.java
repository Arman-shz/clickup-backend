package ir.arman.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POST /api/auth/login (task 2.3).
 *
 * <p>These tests double as the proof that the security-jpa provider verifies the seeded
 * bcrypt hashes: nothing here compares a password, so a green run means the extension
 * read {@code password_hash} in Modular Crypt Format and agreed.
 *
 * <p>The credentials are the ones the spec's own login example uses.
 */
@QuarkusTest
class LoginTest {

    /** paths./api/auth/login.post.responses.401, verbatim from the spec. */
    private static final String BAD_CREDENTIALS = "نام کاربری یا رمز عبور اشتباه است";

    @Inject
    Pool pool;

    @Inject
    JWTParser jwtParser;

    /** Logging in mints refresh tokens; the seeded database must not accumulate them. */
    @AfterEach
    void discardTokensMintedByTheseTests() {
        pool.query("DELETE FROM refresh_tokens").execute().await().indefinitely();
    }

    private static Map<String, String> credentials(String studentId, String password) {
        return Map.of("studentId", studentId, "password", password);
    }

    @Test
    void theSpecsOwnExampleCredentialsSucceed() {
        given().contentType(ContentType.JSON)
                .body(credentials("99100111", "Password123"))
                .when().post("/api/auth/login")
                .then()
                .statusCode(200)
                .body("accessToken", notNullValue())
                .body("refreshToken", matchesRegex(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                .body("user.id", is("usr_101"))
                .body("user.studentId", is("99100111"))
                .body("user.name", is("علی محمدی"))
                .body("user.role", is("student"));
    }

    @Test
    void anAdminGetsTheAdminRoleInTheToken() throws Exception {
        String accessToken = given().contentType(ContentType.JSON)
                .body(credentials("99100112", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .body("user.role", is("admin"))
                .extract().path("accessToken");

        JsonWebToken jwt = jwtParser.parse(accessToken);
        // Read from the @Roles column, so the role in the token is the role in the row.
        assertEquals(Set.of("admin"), jwt.getGroups());
        assertEquals("usr_102", jwt.getName());
    }

    @Test
    void theWrongPasswordIsRejectedWithTheSpecsMessage() {
        given().contentType(ContentType.JSON)
                .body(credentials("99100111", "Password124"))
                .when().post("/api/auth/login")
                .then()
                .statusCode(401)
                .body("success", is(false))
                .body("message", is(BAD_CREDENTIALS));
    }

    @Test
    void anUnknownStudentIdIsIndistinguishableFromAWrongPassword() {
        String unknown = given().contentType(ContentType.JSON)
                .body(credentials("no-such-student", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(401)
                .extract().asString();

        String wrongPassword = given().contentType(ContentType.JSON)
                .body(credentials("99100111", "Password124"))
                .when().post("/api/auth/login")
                .then().statusCode(401)
                .extract().asString();

        // Byte-identical, or the endpoint becomes a student-id oracle.
        assertEquals(wrongPassword, unknown);
    }

    @Test
    void aDeactivatedAccountCannotLogInEvenWithTheRightPassword() {
        // usr_119, seeded with status 'inactive'. Its password is the same as everyone
        // else's, so only the status can be what stops it.
        given().contentType(ContentType.JSON)
                .body(credentials("99100129", "Password123"))
                .when().post("/api/auth/login")
                .then()
                .statusCode(401)
                .body("message", is(BAD_CREDENTIALS));

        assertEquals(0L, liveTokensFor("usr_119"),
                "a rejected login must not leave a refresh token behind");
    }

    @Test
    void aMissingPasswordIsAMalformedRequestRatherThanAFailedLogin() {
        given().contentType(ContentType.JSON)
                .body(Map.of("studentId", "99100111", "password", ""))
                .when().post("/api/auth/login")
                .then()
                .statusCode(400)
                .body("message", is("اطلاعات ورودی نامعتبر است."));
    }

    @Test
    void eachLoginIssuesItsOwnRefreshTokenRatherThanReusingOne() {
        String first = login("99100111", "Password123");
        String second = login("99100111", "Password123");

        assertNotEquals(first, second, "two logins must not share a refresh token");
        assertEquals(2L, liveTokensFor("usr_101"),
                "signing in twice should leave two usable sessions, not replace the first");
    }

    @Test
    void aFreshlyRegisteredAccountCanImmediatelyLogIn() {
        String studentId = "test-login-001";
        try {
            given().contentType(ContentType.JSON)
                    .body(Map.of("name", "سارا رضایی", "studentId", studentId, "password", "Password123"))
                    .when().post("/api/auth/register")
                    .then().statusCode(201);

            // The round trip that matters: PasswordService wrote the hash, and the
            // security-jpa provider is what reads it back.
            given().contentType(ContentType.JSON)
                    .body(credentials(studentId, "Password123"))
                    .when().post("/api/auth/login")
                    .then().statusCode(200)
                    .body("user.studentId", is(studentId))
                    .body("user.role", is("student"));

            given().contentType(ContentType.JSON)
                    .body(credentials(studentId, "Password124"))
                    .when().post("/api/auth/login")
                    .then().statusCode(401);
        } finally {
            pool.preparedQuery("DELETE FROM refresh_tokens WHERE user_id IN "
                            + "(SELECT id FROM users WHERE student_id = $1)")
                    .execute(Tuple.of(studentId))
                    .flatMap(ignored -> pool.preparedQuery("DELETE FROM users WHERE student_id = $1")
                            .execute(Tuple.of(studentId)))
                    .await().indefinitely();
        }
    }

    @Test
    void loginDoesNotLeakTheStoredHash() {
        String body = given().contentType(ContentType.JSON)
                .body(credentials("99100111", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().asString();

        assertTrue(!body.contains("$2a$") && !body.contains("passwordHash") && !body.contains("Password123"),
                "the login response leaked something about the password: " + body);
    }

    private String login(String studentId, String password) {
        return given().contentType(ContentType.JSON)
                .body(credentials(studentId, password))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("refreshToken");
    }

    private long liveTokensFor(String userId) {
        return pool.preparedQuery("""
                        SELECT count(*) AS live FROM refresh_tokens
                        WHERE user_id = $1 AND revoked_at IS NULL AND expires_at > now()
                        """)
                .execute(Tuple.of(userId))
                .map(rows -> rows.iterator().next().getLong("live"))
                .await().indefinitely();
    }
}

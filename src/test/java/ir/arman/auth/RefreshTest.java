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
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * POST /api/auth/refresh (task 2.4).
 *
 * <p>The point of these tests is that refreshing *rotates*: the presented token is spent.
 * A renewal that left the old token live would be indistinguishable in the response and
 * completely different in what a stolen token is worth.
 */
@QuarkusTest
class RefreshTest {

    /** components/responses/Unauthorized, verbatim from the spec. */
    private static final String UNAUTHORIZED = "دسترسی غیرمجاز. لطفا دوباره وارد شوید.";

    @Inject
    Pool pool;

    @Inject
    JWTParser jwtParser;

    @AfterEach
    void discardTokensMintedByTheseTests() {
        pool.query("DELETE FROM refresh_tokens").execute().await().indefinitely();
    }

    /** Signs in as the spec's example account and returns its refresh token. */
    private String signIn() {
        return given().contentType(ContentType.JSON)
                .body(Map.of("studentId", "99100111", "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("refreshToken");
    }

    private static io.restassured.response.Response refreshWith(Object token) {
        Map<String, Object> body = token == null ? Map.of() : Map.of("refreshToken", token);
        return given().contentType(ContentType.JSON).body(body).when().post("/api/auth/refresh");
    }

    @Test
    void aLiveRefreshTokenBuysANewPair() {
        String original = signIn();

        String replacement = refreshWith(original)
                .then()
                .statusCode(200)
                .body("accessToken", notNullValue())
                .body("refreshToken", matchesRegex(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                // The spec's refresh response carries the two tokens and nothing else.
                .body("$", not(hasKey("user")))
                .extract().path("refreshToken");

        assertNotEquals(original, replacement, "refreshing must not hand back the same token");
    }

    @Test
    void theSpentTokenIsRevokedAndCannotBeUsedAgain() {
        String original = signIn();

        refreshWith(original).then().statusCode(200);

        // Second use of the same token: the whole point of rotation.
        refreshWith(original)
                .then()
                .statusCode(401)
                .body("success", is(false))
                .body("message", is(UNAUTHORIZED));

        assertNotNull(revokedAt(original), "the presented token should have been marked revoked");
    }

    @Test
    void theReplacementItselfKeepsWorking() {
        String first = signIn();
        String second = refreshWith(first).then().statusCode(200).extract().path("refreshToken");
        String third = refreshWith(second).then().statusCode(200).extract().path("refreshToken");

        assertNotEquals(second, third);
        // Only the newest link in the chain is live; the two spent ones are not.
        refreshWith(first).then().statusCode(401);
        refreshWith(second).then().statusCode(401);
        refreshWith(third).then().statusCode(200);
    }

    @Test
    void theNewAccessTokenIdentifiesTheSameAccount() throws Exception {
        String accessToken = refreshWith(signIn())
                .then().statusCode(200)
                .extract().path("accessToken");

        JsonWebToken jwt = jwtParser.parse(accessToken);
        assertEquals("usr_101", jwt.getName());
        assertEquals("https://arman.ir/clickup", jwt.getIssuer());
    }

    @Test
    void anUnknownTokenIsRejected() {
        refreshWith(UUID.randomUUID().toString())
                .then().statusCode(401).body("message", is(UNAUTHORIZED));
    }

    @Test
    void somethingThatIsNotAUuidIsRejectedAsUnauthorizedRatherThan400() {
        // The route documents no 400, so a malformed token is simply not a valid one.
        refreshWith("not-a-uuid").then().statusCode(401).body("message", is(UNAUTHORIZED));
        refreshWith("").then().statusCode(401);
        refreshWith(null).then().statusCode(401);
    }

    @Test
    void anExpiredTokenIsRejected() {
        UUID expired = UUID.randomUUID();
        pool.preparedQuery("""
                        INSERT INTO refresh_tokens (token, user_id, issued_at, expires_at)
                        VALUES ($1, 'usr_101', now() - interval '60 days', now() - interval '30 days')
                        """)
                .execute(Tuple.of(expired))
                .await().indefinitely();

        refreshWith(expired.toString()).then().statusCode(401).body("message", is(UNAUTHORIZED));
    }

    @Test
    void aTokenBelongingToADeactivatedAccountStopsWorking() {
        String token = signIn();
        try {
            setStatus("usr_101", "inactive");

            // The token is still live and unexpired; only the account changed.
            refreshWith(token).then().statusCode(401).body("message", is(UNAUTHORIZED));
        } finally {
            setStatus("usr_101", "active");
        }
    }

    @Test
    void refreshingDoesNotDisturbOtherLiveSessions() {
        String sessionA = signIn();
        String sessionB = signIn();

        refreshWith(sessionA).then().statusCode(200);

        // Signing in on a second device must survive a refresh on the first.
        refreshWith(sessionB).then().statusCode(200);
    }

    private String revokedAt(String token) {
        return pool.preparedQuery("SELECT revoked_at FROM refresh_tokens WHERE token = $1")
                .execute(Tuple.of(UUID.fromString(token)))
                .map(rows -> {
                    var row = rows.iterator().next();
                    return row.getValue("revoked_at") == null ? null : row.getValue("revoked_at").toString();
                })
                .await().indefinitely();
    }

    private void setStatus(String userId, String status) {
        pool.preparedQuery("UPDATE users SET status = $1 WHERE id = $2")
                .execute(Tuple.of(status, userId))
                .await().indefinitely();
    }
}

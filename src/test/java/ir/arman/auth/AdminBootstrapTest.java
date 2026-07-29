package ir.arman.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Task 11.6 against the real database: the administrator the deployment asked for exists
 * before anyone has logged in, and can do the one thing only an administrator can.
 *
 * <p>The account is created by the startup observer, not by anything in this class -- by
 * the time the first test method runs it is already there, which is the whole claim. What
 * the tests do is check that it is an administrator rather than merely a row, that logging
 * in as it works, and that a second start would leave it alone.
 *
 * <p>The profile means a separate Quarkus boot, which is unavoidable: the configuration
 * under test is only read once, at startup.
 *
 * <p>Everything is deleted afterwards. {@code MemberResourceTest} asserts the seeded
 * database has exactly 30 users, so an account leaking out of this class fails a test
 * three packages away with no hint of where it came from.
 */
@QuarkusTest
@TestProfile(AdminBootstrapTest.BootstrapAnAdmin.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminBootstrapTest {

    private static final String STUDENT_ID = "test-boot-90000001";
    private static final String PASSWORD = "BootstrapPass123";
    private static final String NAME = "مدیر راه‌اندازی";

    public static class BootstrapAnAdmin implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "app.bootstrap.admin.student-id", STUDENT_ID,
                    "app.bootstrap.admin.password", PASSWORD,
                    "app.bootstrap.admin.name", NAME);
        }
    }

    @Inject
    Pool pool;

    @Inject
    AdminBootstrap bootstrap;

    @AfterEach
    void removeTheRefreshTokensSigningInIssued() {
        pool.query("DELETE FROM refresh_tokens").execute().await().indefinitely();
    }

    @AfterAll
    void removeTheBootstrappedAccount() {
        pool.preparedQuery("DELETE FROM users WHERE student_id = $1")
                .execute(Tuple.of(STUDENT_ID))
                .await().indefinitely();
    }

    @Test
    void theAdministratorExistsBeforeAnybodyHasLoggedIn() {
        Row row = theAccount();

        assertNotNull(row, "startup was configured to create it and did not");
        assertEquals("admin", row.getString("role"),
                "a student here would leave the deployment with no administrator at all,"
                        + " which is the entire problem this solves");
        assertEquals("active", row.getString("status"));
        assertEquals(NAME, row.getString("name"));
        assertEquals("usr_", row.getString("id").substring(0, 4),
                "created through the repository, so it carries a prefixed id like any"
                        + " other account rather than one invented at startup");
    }

    @Test
    void itCanSignInWithTheConfiguredPassword() {
        // The hash has to be one the security-jpa provider accepts. A bootstrap that
        // wrote a password nobody could use would look completely successful in the
        // database and be worthless.
        given().contentType(ContentType.JSON)
                .body(Map.of("studentId", STUDENT_ID, "password", PASSWORD))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .body("user.role", is("admin"))
                .body("user.name", is(NAME));
    }

    @Test
    void itCanReachTheRouteOnlyAnAdministratorCanReach() {
        String token = given().contentType(ContentType.JSON)
                .body(Map.of("studentId", STUDENT_ID, "password", PASSWORD))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");

        // POST /api/logs is admin-only (D9) and is unusable by anybody in a production
        // deployment without this account. This is the end the whole task exists for.
        given().header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(Map.of("level", "info", "message", "[test-116] bootstrap admin reached /api/logs"))
                .when().post("/api/logs")
                .then().statusCode(200)
                .body("success", is(true));
    }

    @Test
    void asecondStartChangesNothing() {
        Row before = theAccount();

        // Standing in for a container restart. If this created a second row the unique
        // constraint on student_id would fail it; what it must not do either is quietly
        // rewrite the account that is already there.
        bootstrap.createFirstAdmin(null);

        Row after = theAccount();
        assertEquals(before.getString("id"), after.getString("id"));
        assertEquals(before.getString("password_hash"), after.getString("password_hash"),
                "re-applying the configured password on every start would silently undo a"
                        + " password change made through the API");
        assertEquals(before.getString("role"), after.getString("role"));
        assertEquals(before.getString("name"), after.getString("name"));
    }

    private Row theAccount() {
        RowSet<Row> rows = pool.preparedQuery(
                        "SELECT id, name, role, status, password_hash FROM users"
                                + " WHERE student_id = $1")
                .execute(Tuple.of(STUDENT_ID))
                .await().indefinitely();

        return rows.iterator().hasNext() ? rows.iterator().next() : null;
    }
}

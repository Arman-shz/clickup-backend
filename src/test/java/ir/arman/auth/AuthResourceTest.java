package ir.arman.auth;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import io.restassured.http.ContentType;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import ir.arman.domain.Language;
import ir.arman.domain.Role;
import ir.arman.domain.Theme;
import ir.arman.domain.User;
import ir.arman.repository.UserRepository;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POST /api/auth/register against the seeded compose Postgres.
 *
 * <p>Registration commits, so every account these tests create is deleted afterwards
 * and the seeded database is left exactly as it was found -- 30 users, no stray
 * refresh tokens. The cleanup goes through the Vert.x client rather than Panache
 * because it runs on the JUnit thread, where there is no Mutiny session.
 */
@QuarkusTest
class AuthResourceTest {

    /** Every account these tests create carries this prefix, so cleanup can find them. */
    private static final String TEST_PREFIX = "test-reg-";

    @Inject
    Pool pool;

    @Inject
    JWTParser jwtParser;

    @Inject
    UserRepository users;

    @AfterEach
    void removeAccountsCreatedByTheseTests() {
        pool.preparedQuery(
                        "DELETE FROM refresh_tokens WHERE user_id IN "
                                + "(SELECT id FROM users WHERE student_id LIKE $1)")
                .execute(Tuple.of(TEST_PREFIX + "%"))
                .flatMap(ignored -> pool.preparedQuery("DELETE FROM users WHERE student_id LIKE $1")
                        .execute(Tuple.of(TEST_PREFIX + "%")))
                .await().indefinitely();
    }

    private static Map<String, String> registration(String studentId) {
        return Map.of(
                "name", "زهرا کریمی",
                "studentId", studentId,
                "password", "Password123");
    }

    @Test
    void registeringCreatesAStudentAccountAndSignsItIn() {
        given()
                .contentType(ContentType.JSON)
                .body(registration(TEST_PREFIX + "001"))
                .when().post("/api/auth/register")
                .then()
                .statusCode(201)
                .body("accessToken", notNullValue())
                // Opaque UUID, not a JWT -- it means nothing outside refresh_tokens.
                .body("refreshToken", matchesRegex(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                .body("user.id", matchesRegex("usr_\\d+"))
                .body("user.studentId", is(TEST_PREFIX + "001"))
                .body("user.name", is("زهرا کریمی"))
                .body("user.role", is("student"))
                // The three fields registration does not ask about, and the one it cannot set.
                .body("user.email", nullValue())
                .body("user.avatar", nullValue())
                .body("user.theme", is("light"))
                .body("user.language", is("fa"))
                .body("user.notificationsEnabled", is(true));
    }

    @Test
    void theIssuedAccessTokenVerifiesAgainstTheConfiguredPublicKey() throws Exception {
        String accessToken = given()
                .contentType(ContentType.JSON)
                .body(registration(TEST_PREFIX + "002"))
                .when().post("/api/auth/register")
                .then().statusCode(201)
                .extract().path("accessToken");

        // parse(), not decode(): this checks the signature against
        // mp.jwt.verify.publickey.location, so it fails if the signing key and the
        // verification key have drifted apart.
        JsonWebToken jwt = jwtParser.parse(accessToken);

        assertTrue(jwt.getName().matches("usr_\\d+"), "upn should be the user id: " + jwt.getName());
        assertEquals(jwt.getName(), jwt.getSubject(), "upn and sub should both be the user id");
        assertEquals("https://arman.ir/clickup", jwt.getIssuer());
        // groups is what @RolesAllowed reads, so the role has to travel in it.
        assertEquals(Set.of("student"), jwt.getGroups());
        assertEquals(TEST_PREFIX + "002", jwt.getClaim("studentId"));
        assertTrue(jwt.getExpirationTime() > jwt.getIssuedAtTime(), "token should expire after issue");
    }

    @Test
    void theRegisteredPasswordIsStoredAsAVerifiableBcryptHashAndNeverReturned() {
        String body = given()
                .contentType(ContentType.JSON)
                .body(registration(TEST_PREFIX + "003"))
                .when().post("/api/auth/register")
                .then().statusCode(201)
                .extract().asString();

        // Nothing derived from the password may appear in the response.
        assertFalse(body.contains("Password123") || body.contains("passwordHash") || body.contains("$2a$"),
                "the response leaked something about the password: " + body);

        String storedHash = pool
                .preparedQuery("SELECT password_hash FROM users WHERE student_id = $1")
                .execute(Tuple.of(TEST_PREFIX + "003"))
                .map(rows -> rows.iterator().next().getString("password_hash"))
                .await().indefinitely();

        // Modular Crypt Format, which is what @Password expects by default -- the
        // algorithm marker, the cost, then salt and digest. That this hash actually
        // verifies is proved end to end by LoginTest, through the real provider rather
        // than through a second comparison written here.
        assertTrue(storedHash.startsWith("$2a$10$"), "expected a bcrypt hash, got: " + storedHash);
        assertNotEquals("Password123", storedHash);

        // Same password, different row: the salt must differ, or the column would leak
        // which accounts share a password.
        String otherHash = registerAndReadHash(TEST_PREFIX + "003b");
        assertNotEquals(storedHash, otherHash, "two accounts with the same password must not share a hash");
    }

    /**
     * The assumption {@code DataConflictExceptionMapper} rests on. The pre-check in the
     * resource means the mapper is only reached when two identical registrations race,
     * which cannot be provoked from outside, so the exception type is pinned here
     * instead. If Hibernate Reactive ever surfaced a raw PgException, the mapper would
     * stop matching and the race would turn back into a 500.
     */
    @Test
    @RunOnVertxContext
    void aDuplicateStudentIdFailsAsAHibernateConstraintViolation(UniAsserter asserter) {
        asserter.assertFailedWith(() -> Panache.withTransaction(() -> {
            User duplicate = new User();
            // 99100111 belongs to seeded usr_101.
            duplicate.studentId = "99100111";
            duplicate.name = "زهرا کریمی";
            duplicate.passwordHash = "$2a$10$notarealhashbutthecolumnonlyneedsastring00000000000000";
            duplicate.role = Role.STUDENT;
            duplicate.theme = Theme.LIGHT;
            duplicate.language = Language.FA;
            duplicate.notificationsEnabled = true;
            duplicate.status = "active";
            return users.create(duplicate);
        }), org.hibernate.exception.ConstraintViolationException.class);
    }

    @Test
    void aStudentIdCanOnlyBeRegisteredOnce() {
        given().contentType(ContentType.JSON).body(registration(TEST_PREFIX + "004"))
                .when().post("/api/auth/register")
                .then().statusCode(201);

        given().contentType(ContentType.JSON).body(registration(TEST_PREFIX + "004"))
                .when().post("/api/auth/register")
                .then()
                .statusCode(409)
                .body("success", is(false))
                .body("message", is("این شماره دانشجویی قبلا ثبت شده است."))
                // ErrorResponse omits `errors` when there is nothing to list.
                .body("$", not(hasKey("errors")));
    }

    @Test
    void registeringWithAnAlreadySeededStudentIdIsRejected() {
        // usr_101 from the seed -- also the account the spec's own login example uses.
        given().contentType(ContentType.JSON)
                .body(Map.of("name", "دیگری", "studentId", "99100111", "password", "Password123"))
                .when().post("/api/auth/register")
                .then().statusCode(409);
    }

    @Test
    void aTooShortPasswordIsRejectedBeforeAnyAccountIsCreated() {
        given().contentType(ContentType.JSON)
                .body(Map.of("name", "زهرا کریمی", "studentId", TEST_PREFIX + "005", "password", "short"))
                .when().post("/api/auth/register")
                .then()
                .statusCode(400)
                .body("success", is(false))
                .body("message", is("اطلاعات ورودی نامعتبر است."))
                .body("errors", hasItem(matchesRegex("password: .*")));

        assertEquals(0L, countUsersWithStudentId(TEST_PREFIX + "005"),
                "a rejected registration must not leave a row behind");
    }

    @Test
    void aBlankNameIsRejected() {
        given().contentType(ContentType.JSON)
                .body(Map.of("name", "   ", "studentId", TEST_PREFIX + "006", "password", "Password123"))
                .when().post("/api/auth/register")
                .then()
                .statusCode(400)
                .body("errors", hasItem(matchesRegex("name: .*")));
    }

    @Test
    void surroundingWhitespaceIsStrippedRatherThanStored() {
        given().contentType(ContentType.JSON)
                .body(Map.of("name", "  زهرا کریمی  ",
                        "studentId", "  " + TEST_PREFIX + "007  ",
                        "password", "Password123"))
                .when().post("/api/auth/register")
                .then()
                .statusCode(201)
                .body("user.name", is("زهرا کریمی"))
                .body("user.studentId", is(TEST_PREFIX + "007"))
                .body("user.name", not(emptyOrNullString()));

        // Stored under the stripped id, so logging in with the clean value will find it.
        assertEquals(1L, countUsersWithStudentId(TEST_PREFIX + "007"));
    }

    @Test
    void everyRegistrationGetsItsOwnRefreshTokenRow() {
        String first = given().contentType(ContentType.JSON).body(registration(TEST_PREFIX + "008"))
                .when().post("/api/auth/register")
                .then().statusCode(201).extract().path("refreshToken");

        String second = given().contentType(ContentType.JSON).body(registration(TEST_PREFIX + "009"))
                .when().post("/api/auth/register")
                .then().statusCode(201).extract().path("refreshToken");

        assertTrue(!first.equals(second), "two registrations must not share a refresh token");

        Long live = pool.preparedQuery("""
                        SELECT count(*) AS live FROM refresh_tokens
                        WHERE user_id IN (SELECT id FROM users WHERE student_id LIKE $1)
                          AND revoked_at IS NULL AND expires_at > now()
                        """)
                .execute(Tuple.of(TEST_PREFIX + "%"))
                .map(rows -> rows.iterator().next().getLong("live"))
                .await().indefinitely();

        assertEquals(2L, live, "each new account should be left holding one usable refresh token");
    }

    private String registerAndReadHash(String studentId) {
        given().contentType(ContentType.JSON).body(registration(studentId))
                .when().post("/api/auth/register")
                .then().statusCode(201);

        return pool.preparedQuery("SELECT password_hash FROM users WHERE student_id = $1")
                .execute(Tuple.of(studentId))
                .map(rows -> rows.iterator().next().getString("password_hash"))
                .await().indefinitely();
    }

    private long countUsersWithStudentId(String studentId) {
        return pool.preparedQuery("SELECT count(*) AS total FROM users WHERE student_id = $1")
                .execute(Tuple.of(studentId))
                .map(rows -> rows.iterator().next().getLong("total"))
                .await().indefinitely();
    }
}

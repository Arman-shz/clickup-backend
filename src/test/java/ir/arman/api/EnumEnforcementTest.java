package ir.arman.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 3.3: `role` is admin|student, `theme` is light|dark, `language` is fa|en.
 *
 * <p>Three defences, and the tests are arranged around them. The DTOs bind to the enum
 * types, so an unknown value is refused before any query runs. The exception mappers give
 * that refusal the 400 body the spec documents instead of the empty one Quarkus produced.
 * The database CHECK constraints remain the last word.
 */
@QuarkusTest
class EnumEnforcementTest {

    /** components/responses/BadRequest, verbatim from the spec. */
    private static final String BAD_REQUEST = "اطلاعات ورودی نامعتبر است.";

    /** components/responses/NotFound, verbatim from the spec. */
    private static final String NOT_FOUND = "منبع مورد نظر پیدا نشد.";

    private static final String STUDENT_ID_PREFIX = "test-enum-";

    @Inject
    Pool pool;

    private String token;

    @BeforeEach
    void registerAThrowawayAccount() {
        token = given().contentType(ContentType.JSON)
                .body(Map.of("name", "کاربر آزمایشی", "studentId",
                        STUDENT_ID_PREFIX + UUID.randomUUID(), "password", "Password123"))
                .when().post("/api/auth/register")
                .then().statusCode(201)
                .extract().path("accessToken");
    }

    @AfterEach
    void removeIt() {
        pool.preparedQuery("DELETE FROM refresh_tokens WHERE user_id IN "
                        + "(SELECT id FROM users WHERE student_id LIKE $1)")
                .execute(Tuple.of(STUDENT_ID_PREFIX + "%")).await().indefinitely();
        pool.preparedQuery("DELETE FROM users WHERE student_id LIKE $1")
                .execute(Tuple.of(STUDENT_ID_PREFIX + "%")).await().indefinitely();
    }

    private RequestSpecification asTheUser() {
        return given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token);
    }

    private static Map<String, Object> body(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    // ---- the values the spec allows are accepted --------------------------------------

    @Test
    void bothThemesAndBothLanguagesAreAccepted() {
        for (String theme : new String[]{"light", "dark"}) {
            asTheUser().body(body("theme", theme))
                    .when().put("/api/users/me")
                    .then().statusCode(200).body("theme", is(theme));
        }
        for (String language : new String[]{"fa", "en"}) {
            asTheUser().body(body("language", language))
                    .when().put("/api/users/me")
                    .then().statusCode(200).body("language", is(language));
        }
    }

    // ---- anything else is a 400 that says what was allowed ----------------------------

    @Test
    void anUnknownThemeIsRefusedWithTheSpecsBadRequestBodyAndTheAllowedValues() {
        asTheUser().body(body("theme", "blue"))
                .when().put("/api/users/me")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("success", is(false))
                .body("message", is(BAD_REQUEST))
                .body("errors", contains("theme: must be one of [light, dark], was: blue"));
    }

    @Test
    void anUnknownLanguageIsRefusedTheSameWay() {
        asTheUser().body(body("language", "de"))
                .when().put("/api/users/me")
                .then()
                .statusCode(400)
                .body("errors", contains("language: must be one of [fa, en], was: de"));
    }

    @Test
    void caseAndSpacingAreNotQuietlyForgiven() {
        // The spec's enums are lower case. Accepting "Dark" would mean the API has values
        // the document does not list.
        for (String almost : new String[]{"Dark", "DARK", " dark", "dark "}) {
            asTheUser().body(body("theme", almost))
                    .when().put("/api/users/me")
                    .then().statusCode(400).body("message", is(BAD_REQUEST));
        }
    }

    @Test
    void anEmptyStringIsNotAValidEnumValue() {
        asTheUser().body(body("theme", ""))
                .when().put("/api/users/me")
                .then().statusCode(400).body("message", is(BAD_REQUEST));
    }

    @Test
    void aValueOfTheWrongTypeIsAlsoTheSpecsBadRequest() {
        asTheUser().body(body("notificationsEnabled", "yes"))
                .when().put("/api/users/me")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("message", is(BAD_REQUEST))
                .body("errors", contains("notificationsEnabled: invalid value"));
    }

    @Test
    void aBodyThatIsNotJsonAtAllIsRefusedWithTheSameBodyRatherThanAnEmptyOne() {
        asTheUser().body("{\"theme\": ")
                .when().put("/api/users/me")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("message", is(BAD_REQUEST))
                .body("errors", contains("body: malformed JSON"));
    }

    // ---- role is not an input anywhere ------------------------------------------------

    @Test
    void registrationCannotChooseARoleEvenAnAllowedOne() {
        String studentId = STUDENT_ID_PREFIX + UUID.randomUUID();
        Map<String, Object> smuggled = new HashMap<>();
        smuggled.put("name", "کاربر آزمایشی");
        smuggled.put("studentId", studentId);
        smuggled.put("password", "Password123");
        smuggled.put("role", "admin");

        given().contentType(ContentType.JSON).body(smuggled)
                .when().post("/api/auth/register")
                .then().statusCode(201).body("user.role", is("student"));
    }

    @Test
    void noRequestSchemaInTheSpecCarriesARoleAtAll() {
        // Stated as a test because it is the actual enforcement: `role` is validated by
        // there being nowhere to send one. UpdateUserProfileRequest has no such property,
        // so this is ignored rather than applied.
        asTheUser().body(body("role", "admin"))
                .when().put("/api/users/me")
                .then().statusCode(200).body("role", is("student"));
    }

    // ---- the database still has the last word -----------------------------------------

    @Test
    void theCheckConstraintsAreStillThereBehindAllOfThis() {
        // If a later change binds one of these columns to a String again, the API stops
        // rejecting bad values but the database does not.
        for (String constraint : new String[]{
                "users_role_check", "users_theme_check", "users_language_check"}) {
            Row row = pool.preparedQuery(
                            "SELECT count(*) FROM pg_constraint WHERE conname = $1 AND contype = 'c'")
                    .execute(Tuple.of(constraint)).await().indefinitely().iterator().next();
            assertTrue(row.getLong(0) == 1, "missing CHECK constraint: " + constraint);
        }
    }

    // ---- the broad 400 mapper must not swallow other statuses -------------------------

    @Test
    void aMissingRouteIsStillTheSpecsNotFoundAndNotA400() {
        // BadRequestBodyMapper is registered for WebApplicationException, which covers
        // NotFoundException and ForbiddenException too. It has to hand those straight
        // back; this is the test that says so.
        given().when().get("/api/does-not-exist")
                .then()
                .statusCode(404)
                .body("success", is(false))
                .body("message", is(NOT_FOUND));
    }

    @Test
    void aForbiddenRouteIsStillForbidden() {
        given().header("Authorization", "Bearer " + token)
                .when().get("/test-only/protected/admin")
                .then().statusCode(403);
    }
}

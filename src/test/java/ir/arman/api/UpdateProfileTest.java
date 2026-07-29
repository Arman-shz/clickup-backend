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
import static org.hamcrest.Matchers.nullValue;

/**
 * Task 3.2: PUT /api/users/me.
 *
 * <p>These tests mutate the account they run against, so they register their own and
 * delete it afterwards. The 30 seeded rows are read but never written -- every other test
 * class in the suite depends on them being exactly as the changelog left them.
 */
@QuarkusTest
class UpdateProfileTest {

    /** components/responses/Unauthorized, verbatim from the spec. */
    private static final String UNAUTHORIZED = "دسترسی غیرمجاز. لطفا دوباره وارد شوید.";

    /** components/responses/BadRequest, verbatim from the spec. */
    private static final String BAD_REQUEST = "اطلاعات ورودی نامعتبر است.";

    /** The 409 this route documents. */
    private static final String EMAIL_TAKEN = "این ایمیل قبلا ثبت شده است.";

    /** Marks the throwaway accounts so the cleanup can find them. */
    private static final String STUDENT_ID_PREFIX = "test-profile-";

    @Inject
    Pool pool;

    private String token;

    @BeforeEach
    void registerAThrowawayAccount() {
        String studentId = STUDENT_ID_PREFIX + UUID.randomUUID();
        token = given().contentType(ContentType.JSON)
                .body(Map.of("name", "کاربر آزمایشی", "studentId", studentId,
                        "password", "Password123"))
                .when().post("/api/auth/register")
                .then().statusCode(201)
                .extract().path("accessToken");
    }

    @AfterEach
    void removeIt() {
        // Tokens first: refresh_tokens.user_id is a foreign key.
        pool.preparedQuery("DELETE FROM refresh_tokens WHERE user_id IN "
                        + "(SELECT id FROM users WHERE student_id LIKE $1)")
                .execute(Tuple.of(STUDENT_ID_PREFIX + "%"))
                .await().indefinitely();
        pool.preparedQuery("DELETE FROM users WHERE student_id LIKE $1")
                .execute(Tuple.of(STUDENT_ID_PREFIX + "%"))
                .await().indefinitely();
    }

    private RequestSpecification asTheUser() {
        return given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token);
    }

    /** Jackson needs a map that tolerates nulls, which Map.of does not. */
    private static Map<String, Object> body(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    // ---- what an update does -------------------------------------------------------

    @Test
    void everyEditableFieldChangesAndComesBackInTheResponse() {
        Map<String, Object> update = new HashMap<>();
        update.put("name", "علی محمدی (مدیر)");
        update.put("email", "profile.test@example.com");
        update.put("avatar", "https://cdn.example.com/avatars/updated.jpg");
        update.put("theme", "dark");
        update.put("language", "en");
        update.put("notificationsEnabled", false);

        asTheUser().body(update)
                .when().put("/api/users/me")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("name", is("علی محمدی (مدیر)"))
                .body("email", is("profile.test@example.com"))
                .body("avatar", is("https://cdn.example.com/avatars/updated.jpg"))
                .body("theme", is("dark"))
                .body("language", is("en"))
                .body("notificationsEnabled", is(false));
    }

    @Test
    void theChangeSurvivesTheRequestRatherThanOnlyBeingEchoedBack() {
        // The response is built from the entity in the same transaction, so it would look
        // right even if nothing flushed. Re-read it on a fresh request instead.
        asTheUser().body(body("theme", "dark")).when().put("/api/users/me").then().statusCode(200);

        asTheUser().when().get("/api/users/me")
                .then().statusCode(200).body("theme", is("dark"));
    }

    @Test
    void fieldsTheRequestDoesNotMentionAreLeftAlone() {
        asTheUser().body(body("avatar", "https://cdn.example.com/avatars/first.jpg"))
                .when().put("/api/users/me").then().statusCode(200);

        // Only the theme this time. The avatar and the name must survive it.
        asTheUser().body(body("theme", "dark"))
                .when().put("/api/users/me")
                .then()
                .statusCode(200)
                .body("theme", is("dark"))
                .body("avatar", is("https://cdn.example.com/avatars/first.jpg"))
                .body("name", is("کاربر آزمایشی"));
    }

    @Test
    void anExplicitNullIsTreatedAsNotMentionedAndClearsNothing() {
        // The documented limitation. Distinguishing absent from null would need a wrapper
        // type on every property, and the spec asks for neither.
        asTheUser().body(body("avatar", "https://cdn.example.com/avatars/keep.jpg"))
                .when().put("/api/users/me").then().statusCode(200);

        asTheUser().body(body("avatar", null))
                .when().put("/api/users/me")
                .then()
                .statusCode(200)
                .body("avatar", is("https://cdn.example.com/avatars/keep.jpg"));
    }

    @Test
    void anEmptyObjectIsAValidRequestThatChangesNothing() {
        // UpdateUserProfileRequest declares no required properties.
        asTheUser().body(Map.of())
                .when().put("/api/users/me")
                .then()
                .statusCode(200)
                .body("name", is("کاربر آزمایشی"))
                .body("theme", is("light"))
                .body("email", is(nullValue()));
    }

    @Test
    void notificationsCanActuallyBeTurnedOff() {
        // false and "not mentioned" have to be different requests, which is why the DTO
        // holds a Boolean and not a boolean.
        asTheUser().body(body("notificationsEnabled", false))
                .when().put("/api/users/me")
                .then().statusCode(200).body("notificationsEnabled", is(false));

        asTheUser().body(body("theme", "dark"))
                .when().put("/api/users/me")
                .then().statusCode(200).body("notificationsEnabled", is(false));
    }

    @Test
    void surroundingWhitespaceIsStrippedTheWayRegistrationStripsIt() {
        asTheUser().body(body("name", "  نام با فاصله  "))
                .when().put("/api/users/me")
                .then().statusCode(200).body("name", is("نام با فاصله"));
    }

    // ---- what an update cannot do --------------------------------------------------

    @Test
    void anAccountCannotPromoteItselfOrChangeItsIdentity() {
        Map<String, Object> smuggled = new HashMap<>();
        smuggled.put("role", "admin");
        smuggled.put("studentId", "99100111");
        smuggled.put("status", "suspended");
        smuggled.put("id", "usr_101");
        smuggled.put("theme", "dark");

        // Unknown properties are ignored rather than rejected, so the request succeeds --
        // what matters is that none of them landed.
        asTheUser().body(smuggled)
                .when().put("/api/users/me")
                .then()
                .statusCode(200)
                .body("role", is("student"))
                .body("theme", is("dark"));

        asTheUser().when().get("/api/users/me")
                .then().statusCode(200).body("role", is("student"));
    }

    @Test
    void anEmailAnotherAccountAlreadyHoldsIsRefused() {
        asTheUser().body(body("email", "a.mohammadi@example.com"))
                .when().put("/api/users/me")
                .then()
                .statusCode(409)
                .contentType(ContentType.JSON)
                .body("success", is(false))
                .body("message", is(EMAIL_TAKEN));
    }

    @Test
    void keepingYourOwnEmailIsNotACollisionWithYourself() {
        asTheUser().body(body("email", "profile.test@example.com"))
                .when().put("/api/users/me").then().statusCode(200);

        asTheUser().body(body("email", "profile.test@example.com"))
                .when().put("/api/users/me")
                .then().statusCode(200).body("email", is("profile.test@example.com"));
    }

    @Test
    void aMalformedEmailIsRejectedBeforeItReachesTheDatabase() {
        asTheUser().body(body("email", "not-an-address"))
                .when().put("/api/users/me")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("message", is(BAD_REQUEST));
    }

    @Test
    void aBlankNameIsRejectedRatherThanStored() {
        asTheUser().body(body("name", "   "))
                .when().put("/api/users/me")
                .then().statusCode(400).body("message", is(BAD_REQUEST));
    }

    @Test
    void valuesLongerThanTheirColumnAreRejectedRatherThanTruncated() {
        asTheUser().body(body("name", "ا".repeat(256)))
                .when().put("/api/users/me")
                .then().statusCode(400).body("message", is(BAD_REQUEST));

        asTheUser().body(body("avatar", "https://example.com/" + "a".repeat(1024)))
                .when().put("/api/users/me")
                .then().statusCode(400).body("message", is(BAD_REQUEST));
    }

    @Test
    void withoutATokenNothingIsWritten() {
        given().contentType(ContentType.JSON).body(body("theme", "dark"))
                .when().put("/api/users/me")
                .then()
                .statusCode(401)
                .body("message", is(UNAUTHORIZED));
    }
}

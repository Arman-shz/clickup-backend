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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tasks 8.1 and 8.2: GET and POST /api/chat/messages.
 *
 * <p>Every message these tests send begins with the marker below, and the cleanup deletes
 * by it. The 30 seeded messages are read but never written.
 */
@QuarkusTest
class ChatResourceTest {

    /** components/responses/BadRequest, verbatim from the spec. */
    private static final String BAD_REQUEST = "اطلاعات ورودی نامعتبر است.";

    /** components/responses/Unauthorized, verbatim from the spec. */
    private static final String UNAUTHORIZED = "دسترسی غیرمجاز. لطفا دوباره وارد شوید.";

    private static final String MARKER = "[test-8x] ";

    /** usr_101, a student. The seed gives every account the same password. */
    private static final String STUDENT = "99100111";

    /** usr_102, one of the two seeded admins. */
    private static final String ADMIN = "99100112";

    @Inject
    Pool pool;

    private String token;

    @BeforeEach
    void signIn() {
        token = tokenFor(STUDENT);
    }

    @AfterEach
    void removeWhatTheseTestsSaid() {
        pool.preparedQuery("DELETE FROM chat_messages WHERE content LIKE $1")
                .execute(Tuple.of(MARKER + "%")).await().indefinitely();
        pool.query("DELETE FROM refresh_tokens").execute().await().indefinitely();
    }

    private static String tokenFor(String studentId) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("studentId", studentId, "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");
    }

    private static RequestSpecification with(String token) {
        return given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token);
    }

    private RequestSpecification asTheStudent() {
        return with(token);
    }

    private static Map<String, Object> message(String content) {
        Map<String, Object> body = new HashMap<>();
        body.put("content", MARKER + content);
        return body;
    }

    // ---------- 8.1 GET /api/chat/messages ----------

    @Test
    void theHistoryIsTheThirtySeededMessages() {
        asTheStudent().when().get("/api/chat/messages")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", is(30))
                .body("id", everyItem(matchesRegex("msg_\\d+")));
    }

    @Test
    void theHistoryIsOldestFirst() {
        // A transcript is read downwards, and it is the order idx_chat_messages_sent_at
        // was built for.
        List<String> timestamps = asTheStudent().when().get("/api/chat/messages")
                .then().statusCode(200).extract().jsonPath().getList("timestamp");

        List<String> ascending = timestamps.stream().sorted().toList();
        assertEquals(ascending, timestamps, "messages should arrive oldest first");
    }

    @Test
    void everyMessageCarriesExactlyTheChatMessageSchema() {
        List<Map<String, Object>> messages = asTheStudent().when().get("/api/chat/messages")
                .then().statusCode(200).extract().jsonPath().getList("$");

        for (Map<String, Object> message : messages) {
            assertEquals(Set.of("id", "senderId", "senderName", "senderAvatar",
                            "content", "fileUrl", "timestamp"),
                    message.keySet(), "unexpected properties on " + message.get("id"));
        }
    }

    @Test
    void timestampsAreRenderedAsIsoInstants() {
        asTheStudent().when().get("/api/chat/messages")
                .then().body("timestamp",
                        everyItem(matchesRegex("\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z")));
    }

    @Test
    void theHistoryNeedsAToken() {
        given().when().get("/api/chat/messages")
                .then().statusCode(401).body("message", is(UNAUTHORIZED));
    }

    @Test
    void oneRoomIsSharedByEveryone() {
        // ChatMessage declares no channel and no recipient, so what one account sends the
        // next account reads. Asserted so that adding a private channel later has to be a
        // deliberate change to the contract.
        String id = asTheStudent().body(message("پیام مشترک"))
                .when().post("/api/chat/messages")
                .then().statusCode(201).extract().path("id");

        with(tokenFor(ADMIN)).when().get("/api/chat/messages")
                .then().body("find { it.id == '" + id + "' }.content",
                        is(MARKER + "پیام مشترک"));
    }

    // ---------- 8.2 POST /api/chat/messages ----------

    @Test
    void sendReturns201WithTheSenderAndTimestampStampedByTheServer() {
        asTheStudent().body(message("سلام همکاران"))
                .when().post("/api/chat/messages")
                .then()
                .statusCode(201)
                .contentType(ContentType.JSON)
                .body("id", matchesRegex("msg_\\d+"))
                .body("senderId", is("usr_101"))
                .body("senderName", is("علی محمدی"))
                .body("content", is(MARKER + "سلام همکاران"))
                .body("fileUrl", is(nullValue()))
                .body("timestamp", matchesRegex("\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z"));
    }

    @Test
    void theSenderComesFromTheTokenNotTheBody() {
        Map<String, Object> impersonation = message("من مدیر هستم");
        impersonation.put("senderId", "usr_102");
        impersonation.put("senderName", "کس دیگر");

        asTheStudent().body(impersonation)
                .when().post("/api/chat/messages")
                .then()
                .statusCode(201)
                .body("senderId", is("usr_101"))
                .body("senderName", not(is("کس دیگر")));
    }

    @Test
    void aSentMessageIsReadableFromTheHistory() {
        String id = asTheStudent().body(message("ماندگار"))
                .when().post("/api/chat/messages")
                .then().statusCode(201).extract().path("id");

        asTheStudent().when().get("/api/chat/messages")
                .then()
                .body("size()", is(31))
                .body("find { it.id == '" + id + "' }.content", is(MARKER + "ماندگار"));
        // Not asserted to be last: the 30 seeded messages are dated August and September
        // 2026, ahead of the clock, so a message sent now sorts into the middle of them.
        // That is a property of the seed data, not of the ordering -- see the timestamps
        // in changelog 002.
    }

    @Test
    void the201AndTheHistoryAgreeOnEveryProperty() {
        // The one shape rule: a message read live and the same message read back must not
        // differ, or a client would need two parsers for one thing.
        Map<String, Object> created = asTheStudent()
                .body(Map.of("content", MARKER + "یکسان",
                        "fileUrl", "https://example.org/a.pdf"))
                .when().post("/api/chat/messages")
                .then().statusCode(201).extract().jsonPath().getMap("$");

        Map<String, Object> fromHistory = asTheStudent().when().get("/api/chat/messages")
                .then().statusCode(200).extract().jsonPath()
                .getMap("find { it.id == '" + created.get("id") + "' }");

        assertEquals(created, fromHistory);
    }

    @Test
    void fileUrlIsKeptWhenGiven() {
        asTheStudent().body(Map.of("content", MARKER + "فایل پیوست",
                        "fileUrl", "https://example.org/report.pdf"))
                .when().post("/api/chat/messages")
                .then()
                .statusCode(201)
                .body("fileUrl", is("https://example.org/report.pdf"));
    }

    @Test
    void aBlankFileUrlIsStoredAsNullRatherThanAnEmptyString() {
        asTheStudent().body(Map.of("content", MARKER + "بدون فایل", "fileUrl", "   "))
                .when().post("/api/chat/messages")
                .then().statusCode(201).body("fileUrl", is(nullValue()));
    }

    @Test
    void contentIsStripped() {
        asTheStudent().body(Map.of("content", "   " + MARKER + "با فاصله   "))
                .when().post("/api/chat/messages")
                .then().statusCode(201).body("content", is(MARKER + "با فاصله"));
    }

    @Test
    void anEmptyContentIsRefused() {
        asTheStudent().body(Map.of("content", ""))
                .when().post("/api/chat/messages")
                .then().statusCode(400).body("message", notNullValue());
    }

    @Test
    void contentOfOnlySpacesIsRefused() {
        // NOT NULL alone would accept it, and it would push a blank line into everyone's
        // open stream.
        asTheStudent().body(Map.of("content", "     "))
                .when().post("/api/chat/messages")
                .then().statusCode(400);
    }

    @Test
    void aMissingContentIsRefused() {
        asTheStudent().body(Map.of("fileUrl", "https://example.org/a.pdf"))
                .when().post("/api/chat/messages")
                .then().statusCode(400);
    }

    @Test
    void anAbsentBodyIsRefused() {
        asTheStudent().when().post("/api/chat/messages")
                .then().statusCode(400).body("message", is(BAD_REQUEST));
    }

    @Test
    void aFileUrlLongerThanItsColumnIsRefusedAsA400NotA500() {
        asTheStudent().body(Map.of("content", MARKER + "طولانی",
                        "fileUrl", "https://example.org/" + "x".repeat(1024)))
                .when().post("/api/chat/messages")
                .then().statusCode(400);
    }

    @Test
    void sendingNeedsAToken() {
        given().contentType(ContentType.JSON).body(message("بدون توکن"))
                .when().post("/api/chat/messages")
                .then().statusCode(401).body("message", is(UNAUTHORIZED));
    }

    @Test
    void aRefusedMessageIsNotStored() {
        asTheStudent().body(Map.of("content", "   "))
                .when().post("/api/chat/messages")
                .then().statusCode(400);

        asTheStudent().when().get("/api/chat/messages").then().body("size()", is(30));
    }

    @Test
    void persianContentSurvivesTheRoundTrip() {
        String persian = MARKER + "پیام فارسی با «نقل‌قول» و ۱۲۳۴ و نیم‌فاصله‌ی درست";

        String id = asTheStudent().body(Map.of("content", persian))
                .when().post("/api/chat/messages")
                .then().statusCode(201).body("content", is(persian)).extract().path("id");

        String stored = pool.preparedQuery("SELECT content FROM chat_messages WHERE id = $1")
                .execute(Tuple.of(id)).await().indefinitely()
                .iterator().next().getString(0);

        assertEquals(persian, stored);
    }

    @Test
    void theSenderNameIsSnapshottedRatherThanJoined() {
        // A renamed account must not rewrite what the history says it said.
        String id = asTheStudent().body(message("قبل از تغییر نام"))
                .when().post("/api/chat/messages")
                .then().statusCode(201).extract().path("id");

        pool.preparedQuery("UPDATE users SET name = $1 WHERE id = 'usr_101'")
                .execute(Tuple.of("نام تازه")).await().indefinitely();
        try {
            asTheStudent().when().get("/api/chat/messages")
                    .then().body("find { it.id == '" + id + "' }.senderName",
                            is("علی محمدی"));
        } finally {
            pool.preparedQuery("UPDATE users SET name = $1 WHERE id = 'usr_101'")
                    .execute(Tuple.of("علی محمدی")).await().indefinitely();
        }
    }

    @Test
    void idsAreHandedOutFromTheSequenceWithoutReuse() {
        String first = asTheStudent().body(message("یک"))
                .when().post("/api/chat/messages").then().extract().path("id");
        String second = asTheStudent().body(message("دو"))
                .when().post("/api/chat/messages").then().extract().path("id");

        assertTrue(Integer.parseInt(second.substring("msg_".length()))
                        > Integer.parseInt(first.substring("msg_".length())),
                second + " should come after " + first);
    }

    @Test
    void theChatIsNotAWriteOnlyLog() {
        // Nothing in the spec deletes or edits a message, and nothing here invents it.
        asTheStudent().when().delete("/api/chat/messages/msg_201").then().statusCode(404);
        asTheStudent().body(message("ویرایش")).when().put("/api/chat/messages")
                .then().statusCode(405);
    }

    @Test
    void sendingLeavesTheSeededMessagesAlone() {
        asTheStudent().body(message("بی‌ضرر")).when().post("/api/chat/messages")
                .then().statusCode(201);

        // Counted by content rather than by id: the sequence carries on from 230, so a
        // message this test sent would match an id prefix just as the seeded ones do.
        Long seeded = pool.preparedQuery(
                        "SELECT count(*) FROM chat_messages WHERE content NOT LIKE $1")
                .execute(Tuple.of(MARKER + "%")).await().indefinitely()
                .iterator().next().getLong(0);
        assertEquals(30L, seeded);
    }

    @Test
    void everyMessageCarriesItsSenderInline() {
        List<String> senders = asTheStudent().when().get("/api/chat/messages")
                .then().extract().jsonPath().getList("senderId");

        assertEquals(0, senders.stream().filter(s -> s == null).count());
        asTheStudent().when().get("/api/chat/messages")
                .then().body("findAll { it.senderName == null }", hasSize(0));
    }
}

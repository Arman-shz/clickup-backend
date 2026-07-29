package ir.arman.api;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tasks 8.3 and 8.4: GET /api/chat/stream, and the broadcast that feeds it.
 *
 * <p>RestAssured is no use here -- it reads a response to its end, and this one has no
 * end. {@link SseListener} opens a real connection instead and queues what arrives; the
 * same helper drives {@link ChatStreamIT} against the native build.
 *
 * <p>The connection is opened <em>before</em> the message is sent in every test that
 * expects one, because the broadcast has no memory: it reaches whoever is listening at the
 * moment it happens and no one else.
 */
@QuarkusTest
class ChatStreamTest {

    /** components/responses/Unauthorized, verbatim from the spec. */
    private static final String UNAUTHORIZED = "دسترسی غیرمجاز. لطفا دوباره وارد شوید.";

    private static final String MARKER = "[test-83] ";

    /** usr_101, a student. The seed gives every account the same password. */
    private static final String STUDENT = "99100111";

    /** usr_102, one of the two seeded admins. */
    private static final String ADMIN = "99100112";

    @TestHTTPResource("/api/chat/stream")
    URI streamUri;

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

    // ---------- 8.3: the connection itself ----------

    @Test
    void theStreamOpensAsAnEventStream() throws Exception {
        try (SseListener listener = listen(token)) {
            assertEquals(200, listener.status());
            assertTrue(listener.contentType().startsWith("text/event-stream"),
                    "content type was " + listener.contentType());
        }
    }

    @Test
    void theStreamRefusesAnAnonymousConnection() throws Exception {
        // The spec omits the security block on this route; it is applied anyway, because
        // the same messages are behind a 401 at /api/chat/messages. Recorded decision.
        try (SseListener listener = listen(null)) {
            assertEquals(401, listener.status());
            assertTrue(listener.everything().contains(UNAUTHORIZED),
                    "expected the spec's Persian 401 body, got: " + listener.everything());
        }
    }

    @Test
    void theStreamRefusesAForgedToken() throws Exception {
        try (SseListener listener = listen("not.a.jwt")) {
            assertEquals(401, listener.status());
        }
    }

    // ---------- 8.4: the broadcast ----------

    @Test
    void aMessageSentAfterConnectingArrivesOnTheStream() throws Exception {
        try (SseListener listener = listen(token)) {
            send("رسید");

            Map<String, Object> event = listener.nextEvent();
            assertEquals(MARKER + "رسید", event.get("content"));
        }
    }

    @Test
    void theEventCarriesTheWholeMessageInTheSameShapeTheOtherRoutesUse() throws Exception {
        try (SseListener listener = listen(token)) {
            Map<String, Object> created = send("یک شکل").jsonPath().getMap("$");
            Map<String, Object> streamed = listener.nextEvent();

            assertEquals(Set.of("id", "senderId", "senderName", "senderAvatar",
                    "content", "fileUrl", "timestamp"), streamed.keySet());
            // Property for property the same message the 201 returned -- a client must not
            // need one parser for the live copy and another for the stored one.
            assertEquals(created, streamed);
        }
    }

    @Test
    void everyConnectedClientReceivesTheSameMessage() throws Exception {
        try (SseListener student = listen(token); SseListener admin = listen(tokenFor(ADMIN))) {
            send("برای همه");

            assertEquals(MARKER + "برای همه", student.nextEvent().get("content"));
            assertEquals(MARKER + "برای همه", admin.nextEvent().get("content"));
        }
    }

    @Test
    void aSenderSeesTheirOwnMessageOnTheStream() throws Exception {
        // Not filtered out by sender: the frontend renders one list, and dropping the
        // author's own copy would make it order messages differently from everyone else.
        try (SseListener listener = listen(token)) {
            send("پیام خودم");
            assertEquals("usr_101", listener.nextEvent().get("senderId"));
        }
    }

    @Test
    void whatWasSaidBeforeConnectingIsNotReplayed() throws Exception {
        send("قبل از اتصال");

        try (SseListener listener = listen(token)) {
            send("بعد از اتصال");

            // The first event to arrive is the second message, not the backlog.
            assertEquals(MARKER + "بعد از اتصال", listener.nextEvent().get("content"));
        }
    }

    @Test
    void theStreamStaysOpenForMessageAfterMessage() throws Exception {
        try (SseListener listener = listen(token)) {
            send("اول");
            send("دوم");
            send("سوم");

            assertEquals(MARKER + "اول", listener.nextEvent().get("content"));
            assertEquals(MARKER + "دوم", listener.nextEvent().get("content"));
            assertEquals(MARKER + "سوم", listener.nextEvent().get("content"));
        }
    }

    @Test
    void aRefusedMessageIsNeverBroadcast() throws Exception {
        try (SseListener listener = listen(token)) {
            asTheStudent().body(Map.of("content", "   "))
                    .when().post("/api/chat/messages").then().statusCode(400);
            send("این یکی معتبر است");

            // If the blank one had gone out, it would arrive first.
            assertEquals(MARKER + "این یکی معتبر است", listener.nextEvent().get("content"));
        }
    }

    @Test
    void oneClientDisconnectingDoesNotEndTheStreamForTheRest() throws Exception {
        try (SseListener staying = listen(token)) {
            try (SseListener leaving = listen(tokenFor(ADMIN))) {
                send("هر دو");
                assertEquals(MARKER + "هر دو", leaving.nextEvent().get("content"));
                assertEquals(MARKER + "هر دو", staying.nextEvent().get("content"));
            }

            // The broadcast is shared by every connection; a closed one must not complete
            // or fail it for the others.
            send("فقط یکی");
            assertEquals(MARKER + "فقط یکی", staying.nextEvent().get("content"));
        }
    }

    @Test
    void aMessageIsStoredWhetherOrNotAnyoneIsListening() throws Exception {
        String id = send("بدون شنونده").path("id");

        Long stored = pool.preparedQuery("SELECT count(*) FROM chat_messages WHERE id = $1")
                .execute(Tuple.of(id)).await().indefinitely()
                .iterator().next().getLong(0);
        assertEquals(1L, stored);
    }

    @Test
    void aStreamedMessageIsAlreadyInTheDatabaseWhenItArrives() throws Exception {
        // The publish is chained onto the committed write rather than done inside the
        // transaction, so a client can act on an event without racing the commit.
        try (SseListener listener = listen(token)) {
            send("پس از ثبت");
            String id = (String) listener.nextEvent().get("id");

            String content = pool.preparedQuery(
                            "SELECT content FROM chat_messages WHERE id = $1")
                    .execute(Tuple.of(id)).await().indefinitely()
                    .iterator().next().getString(0);
            assertEquals(MARKER + "پس از ثبت", content);
        }
    }

    @Test
    void nothingArrivesWhenNothingIsSaid() throws Exception {
        try (SseListener listener = listen(token)) {
            Thread.sleep(SseListener.SUBSCRIPTION_SETTLE_MILLIS * 2);
            assertNull(listener.pollEvent(1), "an idle stream should stay silent");
        }
    }

    // ---------- helpers ----------

    private static String tokenFor(String studentId) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("studentId", studentId, "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");
    }

    private RequestSpecification asTheStudent() {
        return given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token);
    }

    /** Sends a message as the student and returns the 201. */
    private ExtractableResponse<Response> send(String content) {
        return asTheStudent().body(Map.of("content", MARKER + content))
                .when().post("/api/chat/messages")
                .then().statusCode(201).extract();
    }

    private SseListener listen(String bearer) throws Exception {
        return SseListener.open(streamUri, bearer);
    }

}

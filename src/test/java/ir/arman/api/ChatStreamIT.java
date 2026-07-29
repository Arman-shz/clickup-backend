package ir.arman.api;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 8.5: the chat stream against the packaged build, native included.
 *
 * <p>This is the one part of phase 8 a JVM test cannot answer. Server-sent events reach
 * the client through a different message-body writer than a plain JSON response, and a
 * native image only carries the reflection metadata the build could work out ahead of
 * time. So the question -- does a ChatMessage still serialise when it goes out as an SSE
 * event rather than as a response body -- is only settled by running it.
 *
 * <p>Deliberately narrower than {@link ChatStreamTest}: the behaviour of the broadcast is
 * that class's job and is not re-asserted here. What is asserted is that the route exists
 * in the packaged build, that it is still behind the token, and that an event carrying
 * every property of the schema arrives.
 *
 * <p>There is no CDI here, so the message this sends is cleaned up over JDBC rather than
 * through the injected pool -- otherwise it would be left behind, and the next run of
 * {@link ChatResourceTest}, which counts on exactly the 30 seeded messages, would fail.
 */
@QuarkusIntegrationTest
class ChatStreamIT {

    /** components/responses/Unauthorized, verbatim from the spec. */
    private static final String UNAUTHORIZED = "دسترسی غیرمجاز. لطفا دوباره وارد شوید.";

    private static final String MARKER = "[test-85] ";

    /** usr_101, a student. The seed gives every account the same password. */
    private static final String STUDENT = "99100111";

    @TestHTTPResource("/api/chat/stream")
    URI streamUri;

    private String token;

    @BeforeEach
    void signIn() {
        token = given().contentType(ContentType.JSON)
                .body(Map.of("studentId", STUDENT, "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");
    }

    @AfterEach
    void removeWhatThisTestSaid() throws Exception {
        // The same defaults application.properties uses, overridable the same way, so this
        // follows the database the packaged application was pointed at.
        String url = System.getenv().getOrDefault("DB_JDBC_URL",
                "jdbc:postgresql://localhost:5432/clickup");
        String user = System.getenv().getOrDefault("DB_USERNAME", "clickup");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "clickup");

        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM chat_messages WHERE content LIKE ?")) {
            statement.setString(1, MARKER + "%");
            statement.executeUpdate();
        }
        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM refresh_tokens")) {
            statement.executeUpdate();
        }
    }

    @Test
    void theStreamIsStillAnEventStreamInThePackagedBuild() throws Exception {
        try (SseListener listener = SseListener.open(streamUri, token)) {
            assertEquals(200, listener.status());
            assertTrue(listener.contentType().startsWith("text/event-stream"),
                    "content type was " + listener.contentType());
        }
    }

    @Test
    void theStreamIsStillBehindTheTokenInThePackagedBuild() throws Exception {
        try (SseListener listener = SseListener.open(streamUri, null)) {
            assertEquals(401, listener.status());
            assertTrue(listener.everything().contains(UNAUTHORIZED),
                    "expected the spec's Persian 401 body, got: " + listener.everything());
        }
    }

    @Test
    void aBroadcastMessageStillSerialisesWithoutAJitToFallBackOn() throws Exception {
        try (SseListener listener = SseListener.open(streamUri, token)) {
            Map<String, Object> created = given().contentType(ContentType.JSON)
                    .header("Authorization", "Bearer " + token)
                    .body(Map.of("content", MARKER + "پیام native"))
                    .when().post("/api/chat/messages")
                    .then().statusCode(201).extract().jsonPath().getMap("$");

            Map<String, Object> streamed = listener.nextEvent();

            // Every property, not just the text: a missing one would mean the record's
            // components were not reachable for reflection in the image.
            assertEquals(Set.of("id", "senderId", "senderName", "senderAvatar",
                    "content", "fileUrl", "timestamp"), streamed.keySet());
            assertEquals(created, streamed);
            assertEquals(MARKER + "پیام native", streamed.get("content"));
        }
    }
}

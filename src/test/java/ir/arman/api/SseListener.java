package ir.arman.api;

import io.restassured.path.json.JsonPath;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * One open server-sent-events connection, for the tests of /api/chat/stream.
 *
 * <p>RestAssured cannot be used here: it reads a response to its end, and a stream has no
 * end. So the JDK client opens the connection, the body is drained lazily on a thread of
 * its own, and a test waits on the queue that thread fills.
 *
 * <p>Shared by the {@code @QuarkusTest} and the {@code @QuarkusIntegrationTest} so the JVM
 * build and the native build are held to the same assertions.
 */
final class SseListener implements AutoCloseable {

    /** Long enough that a slow machine is not mistaken for a broken broadcast. */
    static final int WAIT_SECONDS = 15;

    /**
     * Between the client seeing the response headers and the server having a live
     * subscription there is a small window. Nothing in the protocol closes it, so the
     * tests wait it out rather than race it.
     */
    static final long SUBSCRIPTION_SETTLE_MILLIS = 300;

    private final HttpClient client;
    private final HttpResponse<Stream<String>> response;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final StringBuilder everything = new StringBuilder();
    private final Thread reader;

    /** Opens the stream and returns once the headers are in and the server has settled. */
    static SseListener open(URI streamUri, String bearer) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder request = HttpRequest.newBuilder(streamUri)
                .header("Accept", "text/event-stream")
                .GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }

        SseListener listener =
                new SseListener(client, client.send(request.build(), BodyHandlers.ofLines()));
        Thread.sleep(SUBSCRIPTION_SETTLE_MILLIS);
        return listener;
    }

    private SseListener(HttpClient client, HttpResponse<Stream<String>> response) {
        this.client = client;
        this.response = response;
        this.reader = Thread.ofVirtual().start(() -> {
            try (Stream<String> body = response.body()) {
                body.forEach(line -> {
                    synchronized (everything) {
                        everything.append(line).append('\n');
                    }
                    if (!line.isBlank()) {
                        lines.add(line);
                    }
                });
            } catch (RuntimeException disconnected) {
                // Closing the client cuts the body mid-read. That is how a test ends.
            }
        });
    }

    int status() {
        return response.statusCode();
    }

    String contentType() {
        return response.headers().firstValue("content-type").orElse("");
    }

    /** The whole body, for the error responses that do have an end. */
    String everything() throws InterruptedException {
        reader.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        synchronized (everything) {
            return everything.toString();
        }
    }

    /** The next {@code data:} payload, parsed. Fails the test if none arrives in time. */
    Map<String, Object> nextEvent() throws InterruptedException {
        Map<String, Object> event = pollEvent(WAIT_SECONDS);
        assertNotNull(event, "no event arrived within " + WAIT_SECONDS + "s");
        return event;
    }

    /** The next {@code data:} payload, or null if the stream stays quiet. */
    Map<String, Object> pollEvent(int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return null;
            }
            String line = lines.poll(left, TimeUnit.NANOSECONDS);
            if (line == null) {
                return null;
            }
            // Field lines other than `data:` -- comments, ids, retry hints -- are not
            // events and are skipped rather than parsed.
            if (line.startsWith("data:")) {
                return JsonPath.from(line.substring("data:".length()).strip()).getMap("$");
            }
        }
    }

    @Override
    public void close() {
        client.shutdownNow();
        try {
            reader.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

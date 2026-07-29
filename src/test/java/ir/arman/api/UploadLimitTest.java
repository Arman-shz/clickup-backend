package ir.arman.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.http.ContentType;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * Task 9.2: an upload over the cap is refused with the spec's error body, not a bare 413
 * and not a 500.
 *
 * <p>The cap is lowered to 1 KiB for this class rather than posting 50 MiB from a test.
 * The branch under test is the same one either way -- {@code file.size() > maxBytes} --
 * and the real number is asserted separately, in
 * {@link UploadResourceTest#theDocumentedCapIsFiftyMebibytes()}, by reading the
 * configuration. Sending 50 MiB through RestAssured would have cost a minute and half a
 * gigabyte of heap to learn the same thing.
 *
 * <p>A profile means a second Quarkus boot, which is why this is one test in its own
 * class and not a case inside {@link UploadResourceTest}.
 */
@QuarkusTest
@TestProfile(UploadLimitTest.TinyCap.class)
class UploadLimitTest {

    /** paths./api/upload.post.responses.413, verbatim from the spec. */
    private static final String TOO_LARGE = "حجم فایل بیشتر از حد مجاز است. حداکثر ۵۰ مگابایت.";

    public static class TinyCap implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.upload.max-bytes", "1024");
        }
    }

    @Inject
    Pool pool;

    private String token;

    @AfterEach
    void removeTheRefreshTokenSigningInIssued() {
        pool.query("DELETE FROM refresh_tokens").execute().await().indefinitely();
    }

    @BeforeEach
    void signIn() {
        token = given().contentType(ContentType.JSON)
                .body(Map.of("studentId", "99100111", "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");
    }

    @Test
    void aFileOverTheCapIsRefusedWithTheSpecsErrorBody() {
        byte[] tooBig = new byte[1025];

        given().header("Authorization", "Bearer " + token)
                .multiPart("file", "big.bin", tooBig, "application/octet-stream")
                .when().post("/api/upload")
                .then().statusCode(413)
                .body("success", is(false))
                .body("message", is(TOO_LARGE));
    }

    @Test
    void aFileExactlyAtTheCapIsAccepted() throws IOException {
        // The cap is inclusive: the spec says "up to 50 MB", so 50 MB is allowed. Off by
        // one here would reject a file the documentation promises to take.
        byte[] exactly = "x".repeat(1024).getBytes(StandardCharsets.UTF_8);

        String filename = given().header("Authorization", "Bearer " + token)
                .multiPart("file", "exact.bin", exactly, "application/octet-stream")
                .when().post("/api/upload")
                .then().statusCode(200)
                .body("size", is(1024))
                .extract().path("filename");

        Files.deleteIfExists(Path.of("target/uploads").resolve(filename));
    }
}

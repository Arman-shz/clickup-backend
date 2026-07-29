package ir.arman.api;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tasks 9.1 and 9.2: POST /api/upload, and the GET /uploads/{filename} that was added
 * alongside it because the spec returns a url and then declares nothing that answers at it.
 *
 * <p>Most of what is asserted here is about the name. The filename arrives from the client
 * and ends up both on a disk and in a url, which makes it the one part of an upload that
 * can do damage on its own -- so it is tested with a path in it, with a leading dot, with
 * Persian in it, and twice with the same value.
 *
 * <p>Every file these tests create is deleted afterwards. They write under
 * {@code target/uploads} ({@code %test.app.upload.directory}), so a failed run leaves
 * nothing outside the build directory either.
 */
@QuarkusTest
class UploadResourceTest {

    /** components/responses/Unauthorized, verbatim from the spec. */
    private static final String UNAUTHORIZED = "دسترسی غیرمجاز. لطفا دوباره وارد شوید.";

    /** components/responses/NotFound, verbatim from the spec. */
    private static final String NOT_FOUND = "منبع مورد نظر پیدا نشد.";

    /** paths./api/upload.post.responses.400, verbatim. */
    private static final String FILE_REQUIRED = "فایلی ارسال نشده است.";
    private static final String FILE_EMPTY = "فایل ارسال‌شده خالی است.";

    private static final Path UPLOADS = Path.of("target/uploads");

    /** Marks the one chat message this class sends, so the cleanup can find it again. */
    private static final String CHAT_MARKER = "[test-91] ";

    @Inject
    Pool pool;

    @ConfigProperty(name = "app.upload.max-bytes")
    long maxBytes;

    @TestHTTPResource("/api/upload")
    URI uploadUri;

    private String token;

    /** Every filename these tests caused to exist, removed in {@link #removeUploads()}. */
    private final List<String> written = new ArrayList<>();

    @BeforeEach
    void signIn() {
        token = given().contentType(ContentType.JSON)
                .body(Map.of("studentId", "99100111", "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");
    }

    @AfterEach
    void removeWhatTheseTestsMade() throws IOException {
        for (String filename : written) {
            Files.deleteIfExists(UPLOADS.resolve(filename));
        }
        written.clear();

        // The seeded conversation is exactly 30 messages and ChatResourceTest counts them.
        pool.preparedQuery("DELETE FROM chat_messages WHERE content LIKE $1")
                .execute(Tuple.of(CHAT_MARKER + "%"))
                .await().indefinitely();

        // Signing in issues a refresh token, and nothing else would ever remove it.
        pool.query("DELETE FROM refresh_tokens").execute().await().indefinitely();
    }

    // ---------------------------------------------------------------- 9.1 the happy path

    @Test
    void anUploadAnswersWithTheSchemaTheSpecDocuments() {
        byte[] content = "گزارش هفتگی".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> body = upload("report.pdf", content, "application/pdf");

        assertEquals(Set.of("success", "url", "filename", "size", "cloudMetadata"),
                body.keySet(),
                "FileUploadResponse has exactly five properties in the spec");
        assertEquals(true, body.get("success"));
        assertEquals(content.length, ((Number) body.get("size")).intValue(),
                "size is measured on the server, not taken from the client");

        String filename = (String) body.get("filename");
        assertEquals("/uploads/" + filename, body.get("url"));

        @SuppressWarnings("unchecked")
        Map<String, Object> cloud = (Map<String, Object>) body.get("cloudMetadata");
        assertEquals(Set.of("cdnUrl", "provider", "objectKey"), cloud.keySet());
        assertEquals("Google Cloud Object Storage", cloud.get("provider"));
        assertEquals("uploads/" + filename, cloud.get("objectKey"));
        assertEquals("https://cdn.example.com/uploads/" + filename, cloud.get("cdnUrl"));
    }

    @Test
    void theStoredNameIsAnEpochSecondAndThenTheOriginalName() {
        String filename = (String) upload("report.pdf", bytes("x"), "application/pdf")
                .get("filename");

        assertTrue(filename.matches("\\d{10}_report\\.pdf"),
                "the spec's example is 1722150000_report.pdf, was " + filename);
    }

    @Test
    void aPersianFilenameSurvivesTheRoundTrip() throws Exception {
        // Built by hand rather than through RestAssured, which writes the filename into
        // Content-Disposition as US-ASCII and turns every Persian letter into a question
        // mark before the request leaves. A browser sends the raw UTF-8 bytes, which is
        // what this does -- so this test is about the server, not about the client
        // library. FileStoreSanitiseTest covers the sanitiser itself.
        String filename = uploadWithRawMultipart("گزارش-هفتگی.pdf", bytes("x"));

        assertTrue(filename.endsWith("_گزارش-هفتگی.pdf"),
                "Persian letters are letters; sanitising must not eat them. Was " + filename);
    }

    @Test
    void whatWasUploadedIsWhatComesBack() {
        byte[] content = "خطوط\nمتعدد\nبا UTF-8".getBytes(StandardCharsets.UTF_8);
        String url = (String) upload("notes.txt", content, "text/plain").get("url");

        byte[] served = given().header("Authorization", "Bearer " + token)
                .when().get(url)
                .then().statusCode(200)
                .extract().asByteArray();

        assertArrayEquals(content, served, "the bytes must survive the disk round trip");
    }

    @Test
    void theReturnedUrlIsAcceptedAsAChatAttachment() {
        // The one place fileUrl is actually consumed: chat_messages.file_url is
        // VARCHAR(1024), and SendMessageRequest caps it at that. A url this route hands
        // out has to fit, or the two halves of the feature do not join up.
        String url = (String) upload("plan.pdf", bytes("x"), "application/pdf").get("url");

        String messageId = given().header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(Map.of("content", CHAT_MARKER + "پیوست", "fileUrl", url))
                .when().post("/api/chat/messages")
                .then().statusCode(201)
                .body("fileUrl", is(url))
                .extract().path("id");

        assertTrue(messageId.startsWith("msg_"));
    }

    // -------------------------------------------------------------------- 9.1 the naming

    @Test
    void aFilenameCarryingAPathIsReducedToItsLastSegment() {
        String filename = (String) upload("../../etc/passwd", bytes("x"), "text/plain")
                .get("filename");

        assertFalse(filename.contains("/"), "no separator may survive: " + filename);
        assertFalse(filename.contains(".."), "no parent reference may survive: " + filename);
        assertTrue(filename.endsWith("_passwd"), filename);
    }

    @Test
    void aWindowsPathIsReducedTheSameWay() {
        String filename = (String) upload("C:\\Users\\admin\\secret.txt", bytes("x"), "text/plain")
                .get("filename");

        assertFalse(filename.contains("\\"), filename);
        assertTrue(filename.endsWith("_secret.txt"), filename);
    }

    @Test
    void aLeadingDotIsStrippedSoNothingBecomesAHiddenFile() {
        String filename = (String) upload(".bashrc", bytes("x"), "text/plain").get("filename");

        assertTrue(filename.matches("\\d{10}_bashrc"), filename);
    }

    @Test
    void aNameMadeEntirelyOfSeparatorsStillProducesAUsableFile() {
        String filename = (String) upload("../..", bytes("x"), "text/plain").get("filename");

        assertTrue(filename.matches("\\d{10}_file"),
                "sanitising to nothing must fall back to a name, was " + filename);
    }

    @Test
    void twoUploadsOfTheSameNameDoNotOverwriteEachOther() {
        byte[] first = bytes("first");
        byte[] second = bytes("second");

        Map<String, Object> one = upload("same.txt", first, "text/plain");
        Map<String, Object> two = upload("same.txt", second, "text/plain");

        assertNotEquals(one.get("filename"), two.get("filename"),
                "a collision must produce a new name, not a replacement");

        assertArrayEquals(first, download((String) one.get("url")));
        assertArrayEquals(second, download((String) two.get("url")));
    }

    // --------------------------------------------------------------------- 9.2 refusals

    @Test
    void aRequestWithNoFilePartIsRefused() {
        given().header("Authorization", "Bearer " + token)
                .multiPart("attachment", "report.pdf", bytes("x"), "application/pdf")
                .when().post("/api/upload")
                .then().statusCode(400)
                .body("success", is(false))
                .body("message", is(FILE_REQUIRED));
    }

    @Test
    void anEmptyFileIsRefused() {
        given().header("Authorization", "Bearer " + token)
                .multiPart("file", "empty.txt", new byte[0], "text/plain")
                .when().post("/api/upload")
                .then().statusCode(400)
                .body("success", is(false))
                .body("message", is(FILE_EMPTY));
    }

    @Test
    void uploadingWithoutATokenIsRefused() {
        given().multiPart("file", "report.pdf", bytes("x"), "application/pdf")
                .when().post("/api/upload")
                .then().statusCode(401)
                .body("success", is(false))
                .body("message", is(UNAUTHORIZED));
    }

    @Test
    void theDocumentedCapIsFiftyMebibytes() {
        // UploadLimitTest exercises the refusal with the cap lowered to 1 KiB, so this is
        // the assertion that the shipped number is the one the spec documents. 52428800 is
        // 50 * 1024 * 1024 -- the same unit the spec's own example uses for 10 MB.
        assertEquals(52_428_800L, maxBytes,
                "the spec says 50 MB and its example counts in mebibytes");
    }

    @Test
    void theSpecDeclaresOnlyPostOnThisRoute() {
        given().header("Authorization", "Bearer " + token)
                .when().get("/api/upload")
                .then().statusCode(405);
    }

    // ---------------------------------------------------------------- the serve route

    @Test
    void anImageIsServedInlineUnderItsRealType() {
        String url = (String) upload("chart.png", bytes("not really a png"), "image/png")
                .get("url");

        given().header("Authorization", "Bearer " + token)
                .when().get(url)
                .then().statusCode(200)
                .header("Content-Type", startsWith("image/png"))
                .header("Content-Disposition", startsWith("inline"))
                .header("X-Content-Type-Options", is("nosniff"));
    }

    @Test
    void anSvgIsNotTreatedAsAnImage() {
        // An SVG is a document that can run script. Served inline from this origin it
        // would run on this origin -- so it is an attachment like everything else.
        String url = (String) upload("logo.svg",
                bytes("<svg xmlns='http://www.w3.org/2000/svg'></svg>"), "image/svg+xml")
                .get("url");

        given().header("Authorization", "Bearer " + token)
                .when().get(url)
                .then().statusCode(200)
                .header("Content-Type", startsWith("application/octet-stream"))
                .header("Content-Disposition", startsWith("attachment"));
    }

    @Test
    void anHtmlUploadIsNeverServedAsHtml() {
        String url = (String) upload("page.html",
                bytes("<script>alert(1)</script>"), "text/html").get("url");

        given().header("Authorization", "Bearer " + token)
                .when().get(url)
                .then().statusCode(200)
                .header("Content-Type", not(containsString("text/html")))
                .header("Content-Disposition", startsWith("attachment"));
    }

    @Test
    void aPdfIsAnAttachmentToo() {
        String url = (String) upload("report.pdf", bytes("%PDF-1.7"), "application/pdf")
                .get("url");

        given().header("Authorization", "Bearer " + token)
                .when().get(url)
                .then().statusCode(200)
                .header("Content-Disposition", startsWith("attachment"));
    }

    @Test
    void aPersianFilenameIsEncodedInTheContentDispositionHeader() {
        Map<String, Object> body = upload("گزارش.pdf", bytes("x"), "application/pdf");

        given().header("Authorization", "Bearer " + token)
                .when().get((String) body.get("url"))
                .then().statusCode(200)
                // RFC 5987: the raw bytes cannot go in a header, so the name is
                // percent-encoded and flagged as UTF-8.
                .header("Content-Disposition", containsString("filename*=UTF-8''"))
                .header("Content-Disposition", not(containsString("گزارش")));
    }

    @Test
    void downloadingWithoutATokenIsRefused() {
        String url = (String) upload("report.pdf", bytes("x"), "application/pdf").get("url");

        given().when().get(url)
                .then().statusCode(401)
                .body("success", is(false))
                .body("message", is(UNAUTHORIZED));
    }

    @Test
    void aNameThatIsNotThereIsAFourOhFourInTheSpecsShape() {
        given().header("Authorization", "Bearer " + token)
                .when().get("/uploads/1722150000_nothing.pdf")
                .then().statusCode(404)
                .body("success", is(false))
                .body("message", is(NOT_FOUND));
    }

    @Test
    void anEncodedTraversalReachesNothing() {
        // %2F is a slash the router will not have split on, so this is the shape a
        // traversal actually takes. It must not become a path out of the upload
        // directory, and it must not be distinguishable from a name that is simply absent.
        given().header("Authorization", "Bearer " + token)
                .when().get("/uploads/..%2F..%2Fetc%2Fpasswd")
                .then().statusCode(404)
                .body("message", is(NOT_FOUND));
    }

    @Test
    void aNameWithAnUnsanitisedCharacterIsRefusedWithoutTouchingTheDisk() {
        // The serve route accepts a name only if sanitising leaves it unchanged, so a
        // space -- legal on a disk, never produced by this API -- is already a no.
        given().header("Authorization", "Bearer " + token)
                .when().get("/uploads/1722150000_a%20b.pdf")
                .then().statusCode(404)
                .body("message", is(NOT_FOUND));
    }

    // ------------------------------------------------------------------------- helpers

    private Map<String, Object> upload(String filename, byte[] content, String contentType) {
        Response response = given().header("Authorization", "Bearer " + token)
                .multiPart("file", filename, content, contentType)
                .when().post("/api/upload")
                .then().statusCode(200)
                .extract().response();

        Map<String, Object> body = response.jsonPath().getMap("$");
        written.add((String) body.get("filename"));
        return body;
    }

    private byte[] download(String url) {
        return given().header("Authorization", "Bearer " + token)
                .when().get(url)
                .then().statusCode(200)
                .extract().asByteArray();
    }

    /**
     * A multipart POST assembled byte by byte, the way a browser assembles it: the
     * filename goes into the header as UTF-8 rather than being transliterated first.
     * Returns the stored filename.
     */
    private String uploadWithRawMultipart(String filename, byte[] content) throws Exception {
        String boundary = "----clickup" + System.nanoTime();
        var body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uploadUri)
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode(), response.body());
        String stored = new io.restassured.path.json.JsonPath(response.body()).getString("filename");
        written.add(stored);
        return stored;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

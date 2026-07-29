package ir.arman.api;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phases 9 and 10 against the packaged build, native included.
 *
 * <p>It exists because of what phase 8 found: the native image had been answering nearly
 * every route with a 500 since phase 2, and nothing in the JVM suite could see it. These
 * two phases added four DTOs -- one of them with a nested record -- and an enum that
 * nothing persists, which is exactly the shape of thing the build has no reason to
 * register for reflection. A green unit suite says nothing about any of that.
 *
 * <p>Deliberately narrow. The behaviour of uploads, of sanitising and of rotation belongs
 * to {@link UploadResourceTest}, {@link ir.arman.upload.FileStoreSanitiseTest} and
 * {@link ClientLogRotationTest}. What is asserted here is that the routes exist in the
 * packaged build, that every property of the response still serialises, and that the
 * enum still binds -- the three things that can be true on the JVM and false in a native
 * image.
 *
 * <p>No CDI here, so cleanup is over plain JDBC, and the uploaded file is removed through
 * the same directory the packaged application was pointed at.
 */
@QuarkusIntegrationTest
class UploadAndLogIT {

    private static final String MARKER = "[test-9x-native] ";

    /** usr_102 is the seed's admin; /api/logs takes nobody else (D9). */
    private static final String ADMIN = "99100112";

    /**
     * Where the launched process was told to put uploads --
     * {@code quarkus.test.env.UPLOAD_DIRECTORY} in application.properties. Repeated here
     * because this JVM is not the one that got the variable.
     */
    private static final Path UPLOADS = Path.of("target/it-uploads");

    private String token;

    @BeforeEach
    void signIn() {
        token = given().contentType(ContentType.JSON)
                .body(Map.of("studentId", ADMIN, "password", "Password123"))
                .when().post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");
    }

    @AfterEach
    void removeTheRefreshTokenSigningInIssued() throws Exception {
        String url = System.getenv().getOrDefault("DB_JDBC_URL",
                "jdbc:postgresql://localhost:5432/clickup");
        String user = System.getenv().getOrDefault("DB_USERNAME", "clickup");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "clickup");

        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM refresh_tokens")) {
            statement.executeUpdate();
        }
    }

    @Test
    void anUploadStillAnswersWithEveryPropertyOfTheSchema() throws IOException {
        byte[] content = "گزارش native".getBytes(StandardCharsets.UTF_8);

        Map<String, Object> body = given().header("Authorization", "Bearer " + token)
                .multiPart("file", "native.txt", content, "text/plain")
                .when().post("/api/upload")
                .then().statusCode(200)
                .extract().jsonPath().getMap("$");

        assertEquals(Set.of("success", "url", "filename", "size", "cloudMetadata"),
                body.keySet());

        // The nested record is the interesting half: a top-level type can be reachable
        // while the one inside it is not.
        @SuppressWarnings("unchecked")
        Map<String, Object> cloud = (Map<String, Object>) body.get("cloudMetadata");
        assertEquals(Set.of("cdnUrl", "provider", "objectKey"), cloud.keySet());

        byte[] served = given().header("Authorization", "Bearer " + token)
                .when().get((String) body.get("url"))
                .then().statusCode(200)
                .header("X-Content-Type-Options", is("nosniff"))
                .extract().asByteArray();

        assertArrayEquals(content, served);

        Files.deleteIfExists(UPLOADS.resolve((String) body.get("filename")));
    }

    @Test
    void aLogEntryIsStillAcceptedFromAnAdmin() {
        given().header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(Map.of("level", "error", "message", MARKER + "native smoke",
                        "context", Map.of("component", "DashboardChart")))
                .when().post("/api/logs")
                .then().statusCode(200)
                .body("success", is(true));
    }

    @Test
    void theLevelEnumStillBindsInTheImage() {
        // LogLevel is bound through a @JsonCreator and is persisted nowhere, so nothing
        // else in the build would have reached it. If its reflection registration were
        // missing this is a 500, not a 400.
        given().header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(Map.of("level", "fatal", "message", "x"))
                .when().post("/api/logs")
                .then().statusCode(400)
                .body("errors[0]", startsWith("level: must be one of [info, warn, error]"));
    }
}

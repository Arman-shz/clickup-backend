package ir.arman.api;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Task 12.4: the seven routes phase 8 could not have exercised in a native image and
 * nothing since has, run against the packaged build.
 *
 * <p>Phase 8 found that 22 of 25 routes answered every request with a 500 in a native
 * image, because {@code Uni<Response>} gave the build nothing to register for reflection,
 * and that a fully green unit suite had not noticed. 11.2 annotated all 20 DTOs
 * pre-emptively rather than one route at a time, so this class is not hunting for that
 * defect again -- it is standing behind the fix. {@link HealthResourceIT},
 * {@link ChatStreamIT} and {@link UploadAndLogIT} already run register/login,
 * chat and upload/logs through the native binary; this closes the rest of the surface:
 * register's own response shape, refresh, the profile routes, projects, tasks, reports and
 * members -- each asserted for exactly the property set the spec's schema promises, which
 * is the one thing a JVM test cannot settle about the artifact that ships.
 *
 * <p>No CDI here, so cleanup is over plain JDBC against whatever database the packaged
 * process was pointed at, the same defaults {@code application.properties} uses.
 */
@QuarkusIntegrationTest
class CoreResourcesIT {

    private static final String MARKER = "[test-124] ";

    private static final String THROWAWAY_PREFIX = "test-124-";

    @AfterEach
    void removeWhatThisTestMade() throws Exception {
        String url = System.getenv().getOrDefault("DB_JDBC_URL",
                "jdbc:postgresql://localhost:5432/clickup");
        String user = System.getenv().getOrDefault("DB_USERNAME", "clickup");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "clickup");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            delete(connection, "DELETE FROM tasks WHERE title LIKE ?", MARKER + "%");
            delete(connection, "DELETE FROM projects WHERE title LIKE ?", MARKER + "%");
            delete(connection, "DELETE FROM weekly_reports WHERE week_title LIKE ?", MARKER + "%");
            delete(connection, "DELETE FROM refresh_tokens WHERE user_id IN "
                    + "(SELECT id FROM users WHERE student_id LIKE ?)", THROWAWAY_PREFIX + "%");
            delete(connection, "DELETE FROM users WHERE student_id LIKE ?", THROWAWAY_PREFIX + "%");
            delete(connection, "DELETE FROM refresh_tokens", null);
        }
    }

    private static void delete(Connection connection, String sql, String like) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (like != null) {
                statement.setString(1, like);
            }
            statement.executeUpdate();
        }
    }

    /** A fresh account, so this class touches nothing the seed or another test owns. */
    private static Map<String, String> registerAThrowawayAccount() {
        String studentId = THROWAWAY_PREFIX + UUID.randomUUID();
        Map<String, Object> response = given().contentType(ContentType.JSON)
                .body(Map.of("name", "کاربر native", "studentId", studentId,
                        "password", "Password123"))
                .when().post("/api/auth/register")
                .then().statusCode(201)
                .body("$", notNullValue())
                .extract().jsonPath().getMap("$");

        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) response.get("user");

        return Map.of(
                "accessToken", (String) response.get("accessToken"),
                "refreshToken", (String) response.get("refreshToken"),
                "userId", (String) user.get("id"));
    }

    @Test
    void registerStillAnswersTheFullLoginResponseInThePackagedBuild() {
        Map<String, Object> response = given().contentType(ContentType.JSON)
                .body(Map.of("name", "کاربر native", "studentId",
                        THROWAWAY_PREFIX + UUID.randomUUID(), "password", "Password123"))
                .when().post("/api/auth/register")
                .then().statusCode(201)
                .extract().jsonPath().getMap("$");

        assertEquals(Set.of("accessToken", "refreshToken", "user"), response.keySet());

        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) response.get("user");
        assertEquals(Set.of("id", "studentId", "name", "email", "role", "avatar",
                "theme", "language", "notificationsEnabled"), user.keySet());
    }

    @Test
    void refreshStillRotatesInThePackagedBuild() {
        String original = registerAThrowawayAccount().get("refreshToken");

        String replacement = given().contentType(ContentType.JSON)
                .body(Map.of("refreshToken", original))
                .when().post("/api/auth/refresh")
                .then().statusCode(200)
                .body("accessToken", notNullValue())
                .body("refreshToken", matchesRegex(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                .extract().path("refreshToken");

        assertNotEquals(original, replacement);
    }

    @Test
    void theProfileRoutesStillSerialiseEveryPropertyInThePackagedBuild() {
        String token = registerAThrowawayAccount().get("accessToken");

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/users/me")
                .then().statusCode(200)
                .body("$", notNullValue());

        given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token)
                .body(Map.of("name", "نام native"))
                .when().put("/api/users/me")
                .then().statusCode(200)
                .body("name", is("نام native"));
    }

    @Test
    void projectsStillRoundTripInThePackagedBuild() {
        String token = registerAThrowawayAccount().get("accessToken");

        Map<String, Object> created = given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("title", MARKER + "native", "description", "توضیح native"))
                .when().post("/api/projects")
                .then().statusCode(201)
                .extract().jsonPath().getMap("$");

        assertEquals(Set.of("id", "title", "description", "color", "icon", "createdAt"),
                created.keySet());

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/projects")
                .then().statusCode(200)
                .body("find { it.id == '" + created.get("id") + "' }.title",
                        is(MARKER + "native"));
    }

    @Test
    void tasksStillRoundTripInThePackagedBuild() {
        String token = registerAThrowawayAccount().get("accessToken");

        Map<String, Object> created = given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("projectId", "proj_1", "title", MARKER + "native",
                        "status", "todo", "assignees", List.of("usr_101")))
                .when().post("/api/tasks")
                .then().statusCode(201)
                .extract().jsonPath().getMap("$");

        assertEquals(Set.of("id", "projectId", "title", "description", "status",
                "priority", "assignees", "dueDate"), created.keySet());

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/tasks?projectId=proj_1")
                .then().statusCode(200)
                .body("find { it.id == '" + created.get("id") + "' }.assignees",
                        contains("usr_101"));
    }

    @Test
    void reportsStillRoundTripInThePackagedBuild() {
        String token = registerAThrowawayAccount().get("accessToken");

        Map<String, Object> created = given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("weekTitle", MARKER + "native", "hoursWorked", 5,
                        "tasksCompleted", 1))
                .when().post("/api/reports")
                .then().statusCode(201)
                .extract().jsonPath().getMap("$");

        assertEquals(Set.of("id", "userId", "userName", "weekTitle", "hoursWorked",
                "tasksCompleted", "achievements", "challenges", "nextWeekPlan",
                "submittedAt"), created.keySet());

        given().header("Authorization", "Bearer " + token)
                .when().get("/api/reports")
                .then().statusCode(200)
                .body("find { it.id == '" + created.get("id") + "' }.weekTitle",
                        is(MARKER + "native"));
    }

    @Test
    void membersStillSerialisesInThePackagedBuild() {
        String token = registerAThrowawayAccount().get("accessToken");

        List<Map<String, Object>> members = given().header("Authorization", "Bearer " + token)
                .when().get("/api/members")
                .then().statusCode(200)
                .extract().jsonPath().getList("$");

        assertEquals(Set.of("id", "name", "email", "role", "status"), members.get(0).keySet());
    }
}

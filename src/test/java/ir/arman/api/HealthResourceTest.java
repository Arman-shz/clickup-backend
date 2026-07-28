package ir.arman.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * Requires the compose Postgres to be running (`docker compose up -d`): Dev Services is
 * disabled, so there is no throwaway database to fall back on. See task 12.2.
 */
@QuarkusTest
class HealthResourceTest {

    @Test
    void healthReportsOkAndConnectedDatabase() {
        given()
          .when().get("/api/health")
          .then()
             .statusCode(200)
             .contentType("application/json")
             .body("status", is("ok"))
             .body("database", is("connected"))
             // The spec's example is "2026-07-28T08:12:00.000Z" -- UTC with millis.
             .body("timestamp", matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
    }

    @Test
    void smallRyeReadinessIncludesTheDatabaseCheck() {
        given()
          .when().get("/q/health/ready")
          .then()
             .statusCode(200)
             .body("status", is("UP"))
             .body("checks.name", org.hamcrest.Matchers.hasItem("Database connection health check"));
    }

    @Test
    void unknownPathReturnsTheSpecsNotFoundBody() {
        given()
          .when().get("/api/does-not-exist")
          .then()
             .statusCode(404)
             .body("success", is(false))
             .body("message", is("منبع مورد نظر پیدا نشد."));
    }
}

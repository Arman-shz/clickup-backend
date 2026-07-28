package ir.arman.domain;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 1.5: every property in the spec's schemas reaches a column, and the ones the
 * spec makes mandatory are the ones the database makes NOT NULL.
 *
 * Written as a test rather than a read-through so it keeps being true. Hibernate's
 * schema validation already covers entity-to-column; this covers spec-to-entity, which
 * nothing else checks.
 */
@QuarkusTest
class SpecSchemaCoverageTest {

    @Inject
    Pool pool;

    /** Spec property -> entity field, for the properties that are persisted. */
    private static Map<String, String> mapping(String... pairs) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            mapping.put(pairs[i], pairs[i + 1]);
        }
        return mapping;
    }

    /**
     * getDeclaredField, not getField: Panache's build-time enhancement demotes every
     * field except the @Id to protected and generates accessors, so the sources' public
     * fields are not public by the time this runs.
     */
    private void assertFieldsExist(Class<?> entity, Map<String, String> specToEntity) {
        specToEntity.forEach((specProperty, field) -> assertDoesNotThrow(
                () -> entity.getDeclaredField(field),
                entity.getSimpleName() + " has no field for spec property '" + specProperty + "'"));
    }

    @Test
    void userProfileAndTeamMemberAreBothCoveredByUser() {
        // UserProfile. `password` is write-only in the spec and lands in passwordHash.
        assertFieldsExist(User.class, mapping(
                "id", "id",
                "studentId", "studentId",
                "name", "name",
                "email", "email",
                "role", "role",
                "avatar", "avatar",
                "theme", "theme",
                "language", "language",
                "notificationsEnabled", "notificationsEnabled",
                "password", "passwordHash"));

        // TeamMember is the same row through a narrower shape; `status` is its own field.
        assertFieldsExist(User.class, mapping(
                "id", "id",
                "name", "name",
                "email", "email",
                "role", "role",
                "status", "status"));
    }

    @Test
    void projectIsCovered() {
        assertFieldsExist(Project.class, mapping(
                "id", "id",
                "title", "title",
                "description", "description",
                "color", "color",
                "icon", "icon",
                "createdAt", "createdAt"));
    }

    @Test
    void taskIsCovered() {
        assertFieldsExist(Task.class, mapping(
                "id", "id",
                "projectId", "projectId",
                "title", "title",
                "description", "description",
                "status", "status",
                "priority", "priority",
                "assignees", "assignees",
                "dueDate", "dueDate"));
    }

    @Test
    void weeklyReportIsCovered() {
        assertFieldsExist(WeeklyReport.class, mapping(
                "id", "id",
                "userId", "userId",
                "userName", "userName",
                "weekTitle", "weekTitle",
                "hoursWorked", "hoursWorked",
                "tasksCompleted", "tasksCompleted",
                "achievements", "achievements",
                "challenges", "challenges",
                "nextWeekPlan", "nextWeekPlan",
                "submittedAt", "submittedAt"));
    }

    @Test
    void chatMessageIsCovered() {
        // The spec calls it `timestamp`; the column avoids that SQL type keyword.
        assertFieldsExist(ChatMessage.class, mapping(
                "id", "id",
                "senderId", "senderId",
                "senderName", "senderName",
                "senderAvatar", "senderAvatar",
                "content", "content",
                "fileUrl", "fileUrl",
                "timestamp", "sentAt"));
    }

    @Test
    void everyEnumeratedTypeInTheSpecHasEveryValue() {
        assertTrue(Set.of(Role.values()).size() == 2);
        assertTrue(Set.of(Theme.values()).size() == 2);
        assertTrue(Set.of(Language.values()).size() == 2);

        assertTrue(Set.of(TaskStatus.from("todo"), TaskStatus.from("in_progress"),
                TaskStatus.from("review"), TaskStatus.from("done")).size() == 4);
        assertTrue(Set.of(TaskPriority.from("low"), TaskPriority.from("medium"),
                TaskPriority.from("high")).size() == 3);

        assertTrue(Set.of(Role.from("admin"), Role.from("student")).size() == 2);
        assertTrue(Set.of(Theme.from("light"), Theme.from("dark")).size() == 2);
        assertTrue(Set.of(Language.from("fa"), Language.from("en")).size() == 2);
    }

    // ---- nullability ------------------------------------------------------------

    private Uni<Set<String>> nullableColumns(String table) {
        return columns(table, "YES");
    }

    private Uni<Set<String>> requiredColumns(String table) {
        return columns(table, "NO");
    }

    private Uni<Set<String>> columns(String table, String isNullable) {
        return pool.preparedQuery("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = $1 AND is_nullable = $2
                        """)
                .execute(Tuple.of(table, isNullable))
                .map(rows -> StreamSupport.stream(rows.spliterator(), false)
                        .map(row -> row.getString("column_name"))
                        .collect(Collectors.toSet()));
    }

    @Test
    @RunOnVertxContext
    void mandatorySpecFieldsAreNotNullColumns(UniAsserter asserter) {
        // Project requires [id, title].
        asserter.assertThat(() -> requiredColumns("projects"), required -> {
            assertTrue(required.containsAll(Set.of("id", "title")));
        });

        // Task requires [id, title, status]. project_id is additionally NOT NULL
        // because CreateTaskRequest requires projectId, so no task can exist without one.
        asserter.assertThat(() -> requiredColumns("tasks"), required -> {
            assertTrue(required.containsAll(Set.of("id", "title", "status", "project_id")));
        });

        // CreateReportRequest requires weekTitle, hoursWorked, tasksCompleted. The rest
        // of a report is stamped by the server, so those are mandatory too.
        asserter.assertThat(() -> requiredColumns("weekly_reports"), required -> {
            assertTrue(required.containsAll(Set.of(
                    "id", "user_id", "user_name", "week_title",
                    "hours_worked", "tasks_completed", "submitted_at")));
        });

        // POST /api/chat/messages requires content; the sender is stamped.
        asserter.assertThat(() -> requiredColumns("chat_messages"), required -> {
            assertTrue(required.containsAll(Set.of(
                    "id", "sender_id", "sender_name", "content", "sent_at")));
        });

        // LoginRequest requires studentId and password. RegisterRequest adds name.
        // `email` is deliberately absent: registration does not collect one, so it is
        // nullable as of changelog 003 -- see optionalSpecFieldsAreNullableColumns.
        asserter.assertThat(() -> requiredColumns("users"), required -> {
            assertTrue(required.containsAll(Set.of(
                    "id", "student_id", "name", "password_hash",
                    "role", "theme", "language", "notifications_enabled", "status")));
        });
    }

    @Test
    @RunOnVertxContext
    void optionalSpecFieldsAreNullableColumns(UniAsserter asserter) {
        asserter.assertThat(() -> nullableColumns("projects"),
                nullable -> assertTrue(nullable.containsAll(Set.of("description", "color", "icon"))));

        asserter.assertThat(() -> nullableColumns("tasks"),
                nullable -> assertTrue(nullable.containsAll(Set.of("description", "priority", "due_date"))));

        asserter.assertThat(() -> nullableColumns("weekly_reports"),
                nullable -> assertTrue(nullable.containsAll(Set.of(
                        "achievements", "challenges", "next_week_plan"))));

        asserter.assertThat(() -> nullableColumns("chat_messages"),
                nullable -> assertTrue(nullable.containsAll(Set.of("sender_avatar", "file_url"))));

        // avatar and email are the optional fields on UserProfile. UserProfile declares
        // no `required` list at all, so both are within contract; email became optional
        // when registration started creating accounts from a name, a student id and a
        // password only. PUT /api/users/me is what fills it in.
        asserter.assertThat(() -> nullableColumns("users"),
                nullable -> assertTrue(nullable.containsAll(Set.of("avatar", "email"))));

        // Nothing else on the account may be optional: an account with no password hash
        // or no role would be unusable rather than merely incomplete.
        asserter.assertThat(() -> nullableColumns("users"), nullable -> {
            assertFalse(nullable.contains("password_hash"));
            assertFalse(nullable.contains("role"));
            assertFalse(nullable.contains("student_id"));
        });
    }

    @Test
    @RunOnVertxContext
    void refreshTokensRecordEnoughToRejectAToken(UniAsserter asserter) {
        asserter.assertThat(() -> requiredColumns("refresh_tokens"),
                required -> assertTrue(required.containsAll(Set.of("token", "user_id", "expires_at"))));

        // Null revoked_at is what "still live" means, so it must be nullable.
        asserter.assertThat(() -> nullableColumns("refresh_tokens"),
                nullable -> assertTrue(nullable.contains("revoked_at")));
    }

    @Test
    @RunOnVertxContext
    void taskAssigneesIsAKeyedJoinTable(UniAsserter asserter) {
        asserter.assertThat(() -> requiredColumns("task_assignees"),
                required -> assertTrue(required.containsAll(Set.of("task_id", "user_id"))));
    }
}

package ir.arman.repository;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import ir.arman.domain.ChatMessage;
import ir.arman.domain.Project;
import ir.arman.domain.Role;
import ir.arman.domain.Task;
import ir.arman.domain.TaskPriority;
import ir.arman.domain.TaskStatus;
import ir.arman.domain.Theme;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the repositories against the seeded database, since compiling proves
 * nothing about whether the HQL parses or the sequence draw works.
 *
 * Requires the compose Postgres (`docker compose up -d`) with the `seed` context
 * applied, which the test profile does by default.
 */
@QuarkusTest
class RepositoryTest {

    @Inject
    UserRepository users;

    @Inject
    ProjectRepository projects;

    @Inject
    TaskRepository tasks;

    @Inject
    WeeklyReportRepository reports;

    @Inject
    ChatMessageRepository messages;

    @Inject
    RefreshTokenRepository refreshTokens;

    @Test
    @RunOnVertxContext
    void findsTheAccountTheSpecsLoginExampleUses(UniAsserter asserter) {
        asserter.assertThat(() -> Panache.withSession(() -> users.findByStudentId("99100111")), user -> {
            assertEquals("usr_101", user.id);
            assertEquals("علی محمدی", user.name);
            assertEquals(Role.STUDENT, user.role);
            assertEquals(Theme.LIGHT, user.theme);
            assertNotNull(user.passwordHash);
        });
    }

    @Test
    @RunOnVertxContext
    void unknownStudentIdYieldsNothingRatherThanFailing(UniAsserter asserter) {
        asserter.assertThat(() -> Panache.withSession(() -> users.findByStudentId("no-such-student")),
                found -> assertNull(found));
    }

    @Test
    @RunOnVertxContext
    void membersComeBackInPersianCollationOrder(UniAsserter asserter) {
        asserter.assertThat(() -> Panache.withSession(() -> users.listMembers()), members -> {
            assertEquals(30, members.size());
            // ICU `fa` sorts آ before ا; a C-collated database would not.
            assertEquals("آرش نجفی", members.get(0).name);
            assertEquals("آیدا حیدری", members.get(1).name);
        });
    }

    @Test
    @RunOnVertxContext
    void taskListLoadsAssigneesWithoutAQueryPerRow(UniAsserter asserter) {
        asserter.assertThat(() -> Panache.withSession(() -> tasks.listFiltered(null, null)), all -> {
            assertEquals(30, all.size());
            Task first = all.stream().filter(t -> t.id.equals("task_1")).findFirst().orElseThrow();
            // Touching the collection outside a session would fail if it were not fetched.
            assertEquals(2, first.assignees.size());
            assertTrue(first.assignees.contains("usr_101"));
            assertTrue(first.assignees.contains("usr_103"));
        });
    }

    @Test
    @RunOnVertxContext
    void taskListAppliesEachFilterAndBoth(UniAsserter asserter) {
        asserter.assertThat(() -> Panache.withSession(() -> tasks.listFiltered("proj_1", null)),
                byProject -> assertEquals(3, byProject.size()));

        asserter.assertThat(() -> Panache.withSession(() -> tasks.listFiltered(null, TaskStatus.DONE)),
                byStatus -> assertTrue(byStatus.stream().allMatch(t -> t.status == TaskStatus.DONE)));

        asserter.assertThat(() -> Panache.withSession(() -> tasks.listFiltered("proj_1", TaskStatus.DONE)), byBoth -> {
            assertEquals(1, byBoth.size());
            assertEquals("task_2", byBoth.get(0).id);
        });
    }

    @Test
    @RunOnVertxContext
    void tasksWithoutADueDateSortLast(UniAsserter asserter) {
        // Insert, query and clean up inside one transaction, so a failure part way
        // through rolls the row back instead of leaving it in the seeded data.
        asserter.assertThat(() -> Panache.withTransaction(() -> {
            Task undated = new Task();
            undated.projectId = "proj_1";
            undated.title = "بدون سررسید";
            undated.status = TaskStatus.TODO;

            return tasks.create(undated)
                    .flatMap(created -> tasks.listFiltered("proj_1", null)
                            .flatMap(ordered -> tasks.delete(created).replaceWith(ordered)));
        }), ordered -> {
            assertEquals(4, ordered.size());
            assertNull(ordered.get(ordered.size() - 1).dueDate);
            assertEquals(LocalDate.of(2026, 8, 5), ordered.get(0).dueDate);
        });
    }

    @Test
    @RunOnVertxContext
    void createDrawsAPrefixedIdFromTheSequence(UniAsserter asserter) {
        asserter.assertThat(() -> Panache.withTransaction(() -> {
            Project project = new Project();
            project.title = "پروژه آزمایشی";
            project.description = "برای آزمون تولید شناسه";
            return projects.create(project)
                    .flatMap(created -> projects.delete(created).replaceWith(created));
        }), created -> {
            assertTrue(created.id.matches("proj_\\d+"), "unexpected id: " + created.id);
            assertNotNull(created.createdAt);
        });
    }

    @Test
    @RunOnVertxContext
    void sendingAMessageCopiesTheSendersNameAndAvatar(UniAsserter asserter) {
        asserter.assertThat(() -> Panache.withTransaction(
                () -> users.findByStudentId("99100111").flatMap(sender -> {
                    ChatMessage message = new ChatMessage();
                    message.content = "پیام آزمایشی";
                    return messages.send(message, sender)
                            .flatMap(sent -> messages.delete(sent).replaceWith(sent));
                })), sent -> {
            assertTrue(sent.id.matches("msg_\\d+"), "unexpected id: " + sent.id);
            assertEquals("usr_101", sent.senderId);
            assertEquals("علی محمدی", sent.senderName);
            assertEquals("https://cdn.example.com/avatars/user101.jpg", sent.senderAvatar);
            assertNotNull(sent.sentAt);
        });
    }

    @Test
    @RunOnVertxContext
    void reportHistoryIsNewestFirst(UniAsserter asserter) {
        asserter.assertThat(() -> Panache.withSession(() -> reports.listHistory()), history -> {
            assertEquals(30, history.size());
            // Assert the ordering itself rather than a particular id: rep_29 was filed
            // later in the day than rep_30, so the newest is not the highest-numbered.
            for (int i = 1; i < history.size(); i++) {
                assertFalse(history.get(i).submittedAt.isAfter(history.get(i - 1).submittedAt),
                        "report " + history.get(i).id + " is newer than the one before it");
            }
            assertEquals(new java.math.BigDecimal("42.00"), history.get(29).hoursWorked);
        });
    }

    @Test
    @RunOnVertxContext
    void chatHistoryIsOldestFirst(UniAsserter asserter) {
        asserter.assertThat(() -> Panache.withSession(() -> messages.listHistory()), history -> {
            assertEquals(30, history.size());
            assertEquals("msg_201", history.get(0).id);
            assertEquals("سلام همکاران گرامی، گزارش این هفته ثبت شد.", history.get(0).content);
        });
    }

    @Test
    @RunOnVertxContext
    void refreshTokensAreFoundOnlyWhileLiveAndRevokeOnce(UniAsserter asserter) {
        asserter.assertThat(() -> Panache.withTransaction(() -> {
            Instant now = Instant.now();
            return refreshTokens.issue("usr_101", Duration.ofDays(30))
                    .flatMap(issued -> refreshTokens.findUsable(issued.token, now)
                            .flatMap(found -> {
                                assertNotNull(found, "a freshly issued token should be usable");
                                return refreshTokens.revoke(issued.token, now)
                                        .flatMap(revoked -> {
                                            assertTrue(revoked, "first revoke should report a change");
                                            return refreshTokens.revoke(issued.token, now);
                                        })
                                        .flatMap(again -> {
                                            assertFalse(again, "revoking twice should report no change");
                                            return refreshTokens.findUsable(issued.token, now);
                                        })
                                        .flatMap(afterRevoke -> {
                                            assertNull(afterRevoke, "a revoked token must not be usable");
                                            return refreshTokens.delete(issued).replaceWith(issued);
                                        });
                            }));
        }), issued -> {
            assertEquals("usr_101", issued.userId);
            assertTrue(issued.expiresAt.isAfter(issued.issuedAt));
        });
    }

    @Test
    @RunOnVertxContext
    void expiredTokensAreNotUsable(UniAsserter asserter) {
        asserter.assertThat(() -> Panache.withTransaction(
                        // Already expired the moment it is minted.
                        () -> refreshTokens.issue("usr_101", Duration.ofSeconds(-1))
                                .flatMap(issued -> refreshTokens.findUsable(issued.token, Instant.now())
                                        .flatMap(found -> refreshTokens.delete(issued).replaceWith(found)))),
                found -> assertNull(found, "an expired token must not be usable"));
    }

    @Test
    @RunOnVertxContext
    void taskPrioritiesRoundTripThroughTheConverter(UniAsserter asserter) {
        asserter.assertThat(() -> Panache.withSession(() -> tasks.findByIdWithAssignees("task_1")), task -> {
            assertEquals(TaskStatus.IN_PROGRESS, task.status);
            assertEquals(TaskPriority.HIGH, task.priority);
            assertEquals("proj_1", task.projectId);
            assertEquals(LocalDate.of(2026, 8, 15), task.dueDate);
        });
    }
}

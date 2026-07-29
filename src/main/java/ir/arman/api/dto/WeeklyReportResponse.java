package ir.arman.api.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import ir.arman.domain.WeeklyReport;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * The spec's WeeklyReport schema (swagger.yaml, components/schemas/WeeklyReport).
 *
 * <p>{@code userId}, {@code userName} and {@code submittedAt} are stamped by the server
 * and are not in CreateReportRequest -- a report cannot be filed on someone else's behalf
 * or backdated. {@code userName} is the name the author had when they filed, kept as a
 * snapshot on the row, so a renamed account does not rewrite what the history says.
 *
 * <p>{@code submittedAt} is rendered as an explicit ISO-8601 instant, the same shape
 * Project.createdAt uses, rather than left to Jackson's configurable Instant handling.
 *
 * <p>{@code hoursWorked} is a BigDecimal because the column is NUMERIC(6,2) and the spec
 * types it {@code number}: half hours are ordinary, and a double would round them.
 */
@RegisterForReflection
public record WeeklyReportResponse(
        String id,
        String userId,
        String userName,
        String weekTitle,
        BigDecimal hoursWorked,
        int tasksCompleted,
        String achievements,
        String challenges,
        String nextWeekPlan,
        String submittedAt) {

    public static WeeklyReportResponse of(WeeklyReport report) {
        return new WeeklyReportResponse(
                report.id,
                report.userId,
                report.userName,
                report.weekTitle,
                report.hoursWorked,
                report.tasksCompleted,
                report.achievements,
                report.challenges,
                report.nextWeekPlan,
                report.submittedAt == null
                        ? null
                        : DateTimeFormatter.ISO_INSTANT.format(report.submittedAt));
    }
}

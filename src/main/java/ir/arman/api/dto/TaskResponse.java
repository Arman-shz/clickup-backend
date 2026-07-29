package ir.arman.api.dto;

import ir.arman.domain.Task;
import ir.arman.domain.TaskPriority;
import ir.arman.domain.TaskStatus;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The spec's Task schema (swagger.yaml, components/schemas/Task).
 *
 * <p>{@code dueDate} is typed as a plain string there but exampled as {@code 2026-08-15},
 * and the column is a real DATE, so it is rendered as ISO-8601 calendar date -- never a
 * timestamp. The same shape is the only one accepted on the way in.
 *
 * <p>{@code assignees} is always an array, empty rather than null when a task has none:
 * the spec types it as an array, and a client iterating it should not have to null-check.
 * Its order is whatever the collection yields and is not part of the contract -- the
 * schema carries no ordering, and the join that loads it makes none available.
 *
 * <p>{@code status} and {@code priority} serialise through the enums' {@code @JsonValue},
 * so a value outside the documented set cannot leave this API even if one reached the
 * column.
 */
public record TaskResponse(
        String id,
        String projectId,
        String title,
        String description,
        TaskStatus status,
        TaskPriority priority,
        List<String> assignees,
        String dueDate) {

    public static TaskResponse of(Task task) {
        return new TaskResponse(
                task.id,
                task.projectId,
                task.title,
                task.description,
                task.status,
                task.priority,
                List.copyOf(task.assignees),
                task.dueDate == null
                        ? null
                        : DateTimeFormatter.ISO_LOCAL_DATE.format(task.dueDate));
    }
}

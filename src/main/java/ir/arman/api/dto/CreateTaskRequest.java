package ir.arman.api.dto;

import ir.arman.domain.TaskPriority;
import ir.arman.domain.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The spec's CreateTaskRequest (swagger.yaml, components/schemas/CreateTaskRequest), used
 * by both POST /api/tasks and PUT /api/tasks/{id}.
 *
 * <p>Because the same schema serves both, PUT is a <strong>replacement</strong>, exactly
 * as PUT /api/projects/{id} is: an absent property means the same thing on both routes,
 * which is null. Moving a task to another column by sending only
 * {@code {projectId, title, status}} therefore clears its description, priority, due date
 * <em>and its assignees</em>. That is stated in the route's description in the spec too,
 * because it is the one way this endpoint can lose data quietly.
 *
 * <p>{@code status} and {@code priority} are the enums rather than strings, so an
 * undocumented value is refused during binding with a 400 naming the accepted set --
 * task 5.5. Without that the CHECK constraints in changelog 001 would turn a bad value
 * into a 500.
 *
 * <p>{@code dueDate} is a String because the spec types it as one, but the column is a
 * real DATE and only {@code YYYY-MM-DD} is accepted. A full timestamp is refused rather
 * than truncated: silently dropping the time part of {@code 2026-08-15T23:30:00Z} would
 * move a deadline by up to a day for anyone east of UTC.
 *
 * <p>The lengths are the columns in changelog 001; {@code description} is TEXT and so
 * carries none. {@code projectId} and the assignee ids are foreign keys, and the resource
 * checks they exist before inserting -- an unknown id is the caller's mistake, so it is a
 * 400 with the offending id named, not a constraint violation surfacing as a 500.
 */
public record CreateTaskRequest(

        @NotBlank
        @Size(max = 64)
        String projectId,

        @NotBlank
        @Size(max = 255)
        String title,

        String description,

        TaskStatus status,

        TaskPriority priority,

        List<String> assignees,

        String dueDate) {
}

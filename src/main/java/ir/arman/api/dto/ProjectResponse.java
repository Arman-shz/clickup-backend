package ir.arman.api.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import ir.arman.domain.Project;

import java.time.format.DateTimeFormatter;

/**
 * The spec's Project schema (swagger.yaml, components/schemas/Project).
 *
 * <p>{@code createdAt} is typed as a plain string there, with no format and no example,
 * so the shape is chosen here: ISO-8601 at UTC, {@code 2026-07-29T03:27:05Z}. Rendered
 * explicitly rather than left to Jackson's Instant handling, which is configurable and
 * would make the wire format depend on a serialisation setting somewhere else.
 */
@RegisterForReflection
public record ProjectResponse(
        String id,
        String title,
        String description,
        String color,
        String icon,
        String createdAt) {

    public static ProjectResponse of(Project project) {
        return new ProjectResponse(
                project.id,
                project.title,
                project.description,
                project.color,
                project.icon,
                project.createdAt == null
                        ? null
                        : DateTimeFormatter.ISO_INSTANT.format(project.createdAt));
    }
}

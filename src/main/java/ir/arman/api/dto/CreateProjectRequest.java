package ir.arman.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The spec's CreateProjectRequest (swagger.yaml, components/schemas/CreateProjectRequest),
 * used by both POST /api/projects and PUT /api/projects/{id}.
 *
 * <p>Because the same schema serves both, a missing property means the same thing on
 * both: nothing. PUT is therefore a <strong>replacement</strong> -- sending only a title
 * leaves the project with no description, colour or icon. That differs from
 * PUT /api/users/me, which merges, and the difference is not an oversight: the profile
 * route has its own schema in which every property is optional, while this one requires a
 * title, so a client here is already sending the whole resource.
 *
 * <p>The lengths are the columns in changelog 001. Without them an over-long title
 * reaches Postgres and comes back as a 500 instead of the 400 the spec documents.
 * {@code description} is a TEXT column and so carries no limit here either.
 */
public record CreateProjectRequest(

        @NotBlank
        @Size(max = 255)
        String title,

        String description,

        @Size(max = 32)
        String color,

        @Size(max = 64)
        String icon) {
}

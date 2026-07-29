package ir.arman.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The body of POST /api/projects/sync: {@code {"projects": [ ... ]}}, whose items are
 * full Project objects -- ids included, which is what makes the operation an upsert
 * rather than a bulk create.
 *
 * <p>{@code createdAt} is part of the Project schema and so is accepted, and then
 * ignored. It is declared here rather than left to Jackson's silent handling of unknown
 * properties so that the choice is visible: the server owns that timestamp. A row that
 * already exists keeps the one it has, and a row created by this endpoint is stamped
 * now. Honouring a client-supplied value would let any caller backdate a project, and
 * nothing in the spec asks for that.
 */
public record SyncProjectsRequest(

        @NotNull
        @Valid
        List<Project> projects) {

    /**
     * One element of the array. {@code id} and {@code title} are both {@code required} in
     * the spec's Project schema, so both are rejected when missing rather than guessed
     * at -- an element without an id has no upsert target.
     */
    public record Project(

            @NotBlank
            @Size(max = 64)
            String id,

            @NotBlank
            @Size(max = 255)
            String title,

            String description,

            @Size(max = 32)
            String color,

            @Size(max = 64)
            String icon,

            /** Accepted and ignored; see the class comment. */
            String createdAt) {
    }
}

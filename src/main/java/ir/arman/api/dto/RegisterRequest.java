package ir.arman.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The spec's RegisterRequest schema (swagger.yaml, components/schemas/RegisterRequest).
 *
 * <p>Three fields and no more: an account is created from a full name, a login
 * identifier and a password. Everything else on the profile -- email, avatar, theme,
 * language, notifications -- is filled in afterwards through PUT /api/users/me.
 *
 * <p>The maximum lengths are the column widths from changelog 001, enforced here so an
 * over-long value is a documented 400 rather than a constraint violation at insert time.
 */
public record RegisterRequest(

        @NotBlank
        @Size(max = 255)
        String name,

        /** Matches LoginRequest.studentId: a student number or a username. */
        @NotBlank
        @Size(max = 64)
        String studentId,

        /**
         * Minimum eight characters, matching the spec's own "Password123" example.
         *
         * <p>The upper bound is bcrypt's, not a policy: bcrypt hashes at most 72 *bytes*
         * and silently ignores the rest, so two long passwords sharing a prefix would
         * verify against each other. 72 characters is the safe reading of that limit for
         * ASCII; a Persian password is two bytes per character, so the effective ceiling
         * is lower still and the tail of one that long would not be part of the hash.
         */
        @NotBlank
        @Size(min = 8, max = 72)
        String password) {
}

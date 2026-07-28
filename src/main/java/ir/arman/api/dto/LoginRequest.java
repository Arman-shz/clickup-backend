package ir.arman.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The spec's LoginRequest schema (swagger.yaml, components/schemas/LoginRequest).
 *
 * <p>Presence is checked and nothing else. RegisterRequest's rules -- eight characters
 * minimum, 64-character student id -- are deliberately absent here: applying them would
 * answer 400 for a credential that merely cannot be right, and the difference between
 * that and a 401 tells an unauthenticated caller which shapes of student id exist. It
 * would also lock out any account whose password predates the current rules.
 */
public record LoginRequest(

        @NotBlank
        String studentId,

        @NotBlank
        String password) {
}

package ir.arman.api.dto;

import ir.arman.domain.User;

/**
 * The spec's TeamMember schema (swagger.yaml, components/schemas/TeamMember).
 *
 * <p>The same row {@link UserProfileResponse} renders, through a narrower window: five
 * properties instead of nine. {@code studentId}, {@code theme}, {@code language} and
 * {@code notificationsEnabled} are yours and stay on /api/users/me -- the display
 * settings of a colleague are not a team-directory concern, and the login identifier is
 * half of a credential.
 *
 * <p>{@code email} is nullable here as everywhere: registration collects a name, a
 * student id and a password only, so an account exists before it has an address.
 *
 * <p>{@code status} is a free string rather than an enum because the spec gives it no
 * set of values; the seed uses `active` and `inactive`.
 */
public record TeamMemberResponse(
        String id,
        String name,
        String email,
        String role,
        String status) {

    public static TeamMemberResponse of(User user) {
        return new TeamMemberResponse(
                user.id,
                user.name,
                user.email,
                user.role.value(),
                user.status);
    }
}

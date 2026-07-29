package ir.arman.api.dto;

import ir.arman.domain.Language;
import ir.arman.domain.Theme;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The spec's UpdateUserProfileRequest (swagger.yaml,
 * components/schemas/UpdateUserProfileRequest).
 *
 * <p><strong>Every property is optional, and omitting one leaves that field alone.</strong>
 * The schema declares no {@code required} list, so a client sending only
 * {@code {"theme": "dark"}} is making a valid request -- and the only reading of it that
 * does not destroy data is a merge. Treating the absent properties as "set to null" would
 * mean a client toggling a theme also erased its own name, which the database rejects
 * outright for the NOT NULL columns and would silently blank the rest.
 *
 * <p>The consequence, stated plainly because it is a real limitation: there is no way
 * through this endpoint to clear an avatar or an email once set. Distinguishing an absent
 * property from an explicit {@code null} needs a wrapper type on every component, and the
 * spec asks for neither. If clearing is wanted, it belongs in the spec first.
 *
 * <p>{@code theme} and {@code language} are the enums rather than strings, so an
 * unknown value is refused during binding instead of reaching the database's CHECK
 * constraint as a 500. {@code notificationsEnabled} is the boxed {@code Boolean} for the
 * same reason the others are nullable: {@code false} and "not mentioned" are different
 * requests, and a primitive cannot tell them apart.
 *
 * <p>{@code role} is deliberately absent, matching the spec. An account cannot promote
 * itself.
 */
public record UpdateUserProfileRequest(

        // Blank is rejected rather than accepted-and-stored: `name` is NOT NULL and a
        // user with a whitespace name is not a state any client asked for.
        @Size(max = 255)
        @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        String name,

        // 320 is the column, and the address is also checked for shape -- unlike
        // registration, which never collects one.
        @Email
        @Size(max = 320)
        String email,

        @Size(max = 1024)
        String avatar,

        Theme theme,

        Language language,

        Boolean notificationsEnabled) {
}

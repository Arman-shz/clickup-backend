package ir.arman.api.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import ir.arman.domain.User;

/**
 * The spec's UserProfile schema (swagger.yaml, components/schemas/UserProfile).
 *
 * <p>Deliberately a separate type from {@link ir.arman.domain.User} rather than an
 * annotated entity: the entity carries {@code passwordHash}, and a DTO makes it
 * impossible to leak by forgetting an annotation.
 *
 * <p>{@code email} is nullable and serialised as null when absent -- a freshly
 * registered account has no address yet. The spec's UserProfile declares no required
 * properties, so that is within contract.
 */
@RegisterForReflection
public record UserProfileResponse(
        String id,
        String studentId,
        String name,
        String email,
        String role,
        String avatar,
        String theme,
        String language,
        boolean notificationsEnabled) {

    public static UserProfileResponse of(User user) {
        return new UserProfileResponse(
                user.id,
                user.studentId,
                user.name,
                user.email,
                user.role.value(),
                user.avatar,
                user.theme.value(),
                user.language.value(),
                user.notificationsEnabled);
    }
}

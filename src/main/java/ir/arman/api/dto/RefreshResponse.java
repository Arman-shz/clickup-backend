package ir.arman.api.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
/**
 * The inline 200 body of POST /api/auth/refresh
 * (swagger.yaml, paths./api/auth/refresh.post.responses.200).
 *
 * <p>Deliberately not {@link LoginResponse}: the spec's refresh response carries the two
 * tokens and no {@code user}. Refreshing is not a fresh sign-in, and returning the
 * profile would invite clients to treat it as one and refresh in order to poll it.
 */
@RegisterForReflection
public record RefreshResponse(String accessToken, String refreshToken) {

    public static RefreshResponse of(LoginResponse session) {
        return new RefreshResponse(session.accessToken(), session.refreshToken());
    }
}

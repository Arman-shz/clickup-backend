package ir.arman.api.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
/**
 * The spec's LoginResponse schema (swagger.yaml, components/schemas/LoginResponse).
 *
 * <p>Returned by POST /api/auth/register as well as POST /api/auth/login: registering
 * signs the account in, so the client never has to replay the password it just sent.
 *
 * <p>{@code accessToken} is a signed JWT and is verified without touching the database.
 * {@code refreshToken} is an opaque UUID and means nothing except as a row in
 * {@code refresh_tokens} -- which is what makes it revocable.
 */
@RegisterForReflection
public record LoginResponse(
        String accessToken,
        String refreshToken,
        UserProfileResponse user) {
}

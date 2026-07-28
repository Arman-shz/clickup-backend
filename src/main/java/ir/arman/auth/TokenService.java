package ir.arman.auth;

import io.smallrye.jwt.build.Jwt;
import io.smallrye.mutiny.Uni;
import ir.arman.api.dto.LoginResponse;
import ir.arman.api.dto.UserProfileResponse;
import ir.arman.domain.User;
import ir.arman.repository.RefreshTokenRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * Mints the token pair that /api/auth/register, /login and /refresh all return.
 *
 * <p>The two halves are deliberately different kinds of thing. The access token is a
 * signed JWT: verifying it is arithmetic, no database round trip, which is why it is
 * short-lived -- there is no way to withdraw one before it expires. The refresh token is
 * an opaque UUID with no meaning outside {@code refresh_tokens}, so it can be revoked by
 * updating a row, which is why it is allowed to live for weeks.
 */
@ApplicationScoped
public class TokenService {

    @Inject
    RefreshTokenRepository refreshTokens;

    @ConfigProperty(name = "app.auth.access-token.lifespan")
    Duration accessTokenLifespan;

    @ConfigProperty(name = "app.auth.refresh-token.lifespan")
    Duration refreshTokenLifespan;

    /**
     * Signs the caller in: a fresh access token plus a newly persisted refresh token.
     * Must run inside a transaction -- it inserts a row.
     */
    public Uni<LoginResponse> issueFor(User user) {
        return refreshTokens.issue(user.id, refreshTokenLifespan)
                .map(refreshToken -> new LoginResponse(
                        accessTokenFor(user),
                        refreshToken.token.toString(),
                        UserProfileResponse.of(user)));
    }

    /**
     * The claims the rest of the API will read back.
     *
     * <p>{@code groups} carries the role because that is the claim Quarkus maps to
     * {@code @RolesAllowed}; putting the role anywhere else would mean writing the
     * authorisation check by hand. {@code upn} and {@code sub} both carry the user id,
     * so {@code SecurityIdentity.getPrincipal().getName()} identifies the row directly
     * without a lookup by student id.
     */
    private String accessTokenFor(User user) {
        return Jwt.upn(user.id)
                .subject(user.id)
                .groups(user.role.value())
                .claim("studentId", user.studentId)
                .claim("name", user.name)
                .expiresIn(accessTokenLifespan)
                .sign();
    }
}

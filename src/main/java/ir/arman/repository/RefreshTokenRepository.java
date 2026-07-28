package ir.arman.repository;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import ir.arman.domain.RefreshToken;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Refresh tokens are keyed by the token itself rather than a prefixed id, so this one
 * does not extend {@link PrefixedIdRepository}. UUIDs come from the JVM, not a sequence.
 */
@ApplicationScoped
public class RefreshTokenRepository implements PanacheRepositoryBase<RefreshToken, UUID> {

    /** Mints a token for a session. Callers hand the opaque UUID back to the client. */
    public Uni<RefreshToken> issue(String userId, Duration lifetime) {
        Instant now = Instant.now();

        RefreshToken token = new RefreshToken();
        token.token = UUID.randomUUID();
        token.userId = userId;
        token.issuedAt = now;
        token.expiresAt = now.plus(lifetime);

        return persist(token);
    }

    /**
     * The lookup behind POST /api/auth/refresh. Returns nothing for a token that is
     * unknown, expired or already revoked -- all three are the spec's single 401.
     */
    public Uni<RefreshToken> findUsable(UUID token, Instant now) {
        return find("token = :token AND revokedAt IS NULL AND expiresAt > :now",
                Map.of("token", token, "now", now))
                .firstResult();
    }

    /**
     * Revokes a token, returning whether it was still live. Refreshing rotates: the
     * presented token is revoked as the replacement is issued.
     */
    public Uni<Boolean> revoke(UUID token, Instant now) {
        return update("revokedAt = :now WHERE token = :token AND revokedAt IS NULL",
                Map.of("now", now, "token", token))
                .map(updated -> updated > 0);
    }

    /** Ends every live session for an account. */
    public Uni<Integer> revokeAllFor(String userId, Instant now) {
        return update("revokedAt = :now WHERE userId = :userId AND revokedAt IS NULL",
                Map.of("now", now, "userId", userId));
    }
}

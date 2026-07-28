package ir.arman.domain;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An issued refresh token. The spec's refreshToken is an opaque UUID with no signature,
 * so the only way to tell a valid one from an invented one is to look it up here.
 *
 * The token is its own primary key: nothing refers to a refresh token by any other
 * handle, and it is what POST /api/auth/refresh arrives holding.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends PanacheEntityBase {

    @Id
    public UUID token;

    @Column(name = "user_id", nullable = false)
    public String userId;

    @Column(name = "issued_at", nullable = false)
    public Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    /** Set when the token is rotated or the session ends; null while the token is live. */
    @Column(name = "revoked_at")
    public Instant revokedAt;

    public boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}

package ir.arman.api.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
/**
 * The inline request body of POST /api/auth/refresh
 * (swagger.yaml, paths./api/auth/refresh.post.requestBody).
 *
 * <p>No bean-validation constraints, deliberately. That route documents exactly one
 * failure -- 401 -- so a missing or malformed token is answered the same way as an
 * expired or revoked one rather than as a 400 the spec does not describe. Contrast
 * {@link LoginRequest}, whose route does document a 400.
 */
@RegisterForReflection
public record RefreshRequest(String refreshToken) {
}

package ir.arman.api.error;

import io.quarkus.security.AuthenticationFailedException;
import ir.arman.api.dto.ErrorResponse;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Missing, malformed or expired bearer token.
 *
 * <p>Quarkus's default is a bare 401 with a WWW-Authenticate challenge and no body,
 * but the spec documents a JSON body on every 401 (components/responses/Unauthorized).
 */
@Provider
public class AuthenticationFailedExceptionMapper
        implements ExceptionMapper<AuthenticationFailedException> {

    @Override
    public Response toResponse(AuthenticationFailedException exception) {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ErrorResponse.of(ApiMessages.UNAUTHORIZED))
                .build();
    }
}

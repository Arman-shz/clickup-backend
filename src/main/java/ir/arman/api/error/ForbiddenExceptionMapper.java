package ir.arman.api.error;

import io.quarkus.security.ForbiddenException;
import ir.arman.api.dto.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A valid token, but not the role the route requires.
 *
 * <p>Distinct from {@link UnauthorizedExceptionMapper} in status as well as in text: 401
 * tells a client to sign in again, and answering 401 here would send an admin-less account
 * round the login loop forever over something signing in cannot fix. The only route that
 * produces this today is POST /api/logs -- decision D9.
 */
@Provider
public class ForbiddenExceptionMapper implements ExceptionMapper<ForbiddenException> {

    @Override
    public Response toResponse(ForbiddenException exception) {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(ErrorResponse.of(ApiMessages.FORBIDDEN))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}

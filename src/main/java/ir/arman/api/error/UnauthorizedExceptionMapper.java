package ir.arman.api.error;

import io.quarkus.security.UnauthorizedException;
import ir.arman.api.dto.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Authenticated but not permitted to perform the operation.
 *
 * <p>Kept separate from {@link AuthenticationFailedExceptionMapper} so JAX-RS can select
 * by exact exception type rather than by mapper priority; a single
 * {@code ExceptionMapper<Throwable>} would also swallow 404s and validation failures.
 */
@Provider
public class UnauthorizedExceptionMapper implements ExceptionMapper<UnauthorizedException> {

    @Override
    public Response toResponse(UnauthorizedException exception) {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ErrorResponse.of(ApiMessages.UNAUTHORIZED))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}

package ir.arman.api.error;

import ir.arman.api.dto.ErrorResponse;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps a missing resource to the spec's NotFound body, used by the 404 responses on
 * /api/projects/{id} and /api/tasks/{id}. Also covers unmatched routes, so an unknown
 * URL returns the documented error shape instead of Quarkus's default page.
 */
@Provider
public class NotFoundExceptionMapper implements ExceptionMapper<NotFoundException> {

    @Override
    public Response toResponse(NotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ErrorResponse.of(ApiMessages.NOT_FOUND))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}

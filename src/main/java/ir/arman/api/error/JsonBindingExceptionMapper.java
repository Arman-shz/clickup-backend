package ir.arman.api.error;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import ir.arman.api.dto.ErrorResponse;
import jakarta.annotation.Priority;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.List;

/**
 * A body whose JSON is well formed but does not fit the target type -- a string where a
 * boolean belongs, an object where an array does.
 *
 * <p>Quarkus already answers 400 here, so the status was never wrong; the body was. Its
 * builtin mapper emits its own
 * {@code {"objectName":...,"attributeName":...,"line":...,"column":...}} shape, which is
 * not the ErrorResponse the spec documents and which names Java classes at that. This is
 * registered at priority 1 (lower wins) to displace it.
 *
 * <p>Its sibling {@link BadRequestBodyMapper} covers the failures that never reach a
 * Jackson mapper at all.
 */
@Provider
@Priority(1)
public class JsonBindingExceptionMapper implements ExceptionMapper<MismatchedInputException> {

    @Override
    public Response toResponse(MismatchedInputException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ErrorResponse.of(
                        ApiMessages.BAD_REQUEST, List.of(JsonBindingErrors.describe(exception))))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}

package ir.arman.api.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import ir.arman.api.dto.ErrorResponse;
import jakarta.annotation.Priority;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.List;

/**
 * Gives a body to the 400s that were arriving with none.
 *
 * <p>Quarkus's Jackson message body reader does not let every deserialisation failure
 * through to an exception mapper. Some it wraps: {@code new WebApplicationException(cause,
 * BAD_REQUEST)}, a response with a status and nothing else. By the time JAX-RS looks for a
 * mapper the exception is no longer a Jackson type, so no mapper for one is ever
 * consulted, and the client gets 400 with an empty body and no content type. That is the
 * path an unknown enum value takes -- {@code {"theme":"blue"}} -- because the
 * {@code @JsonCreator} throws IllegalArgumentException and Jackson reports it as
 * ValueInstantiationException, which is a JsonMappingException but not a
 * MismatchedInputException. Malformed JSON takes it too.
 *
 * <p>The spec has one 400 body (components/responses/BadRequest) and says nothing about
 * an empty one, so this fills it in. Only for that exact case: status 400 with no entity.
 * Anything else -- a 403, a 404, any WebApplicationException that built its own body --
 * is handed back untouched, which is what makes it safe to map a type this broad.
 *
 * <p>Where the wrapped cause is a Jackson mapping failure the offending field is named,
 * using the same renderer as {@link JsonBindingExceptionMapper} so the two paths are
 * indistinguishable to a client.
 */
@Provider
@Priority(1)
public class BadRequestBodyMapper implements ExceptionMapper<WebApplicationException> {

    @Override
    public Response toResponse(WebApplicationException exception) {
        Response original = exception.getResponse();
        if (original == null
                || original.getStatus() != Response.Status.BAD_REQUEST.getStatusCode()
                || original.hasEntity()) {
            return original;
        }

        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ErrorResponse.of(ApiMessages.BAD_REQUEST, detailOf(exception)))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /** Null rather than an empty list: ErrorResponse.errors is optional in the spec. */
    private static List<String> detailOf(WebApplicationException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof JsonMappingException mapping) {
            return List.of(JsonBindingErrors.describe(mapping));
        }
        if (cause instanceof JsonProcessingException) {
            // A parse failure, not a mapping one: there is no field to name, and the line
            // and column describe a request the client already has in front of it.
            return List.of("body: malformed JSON");
        }
        return null;
    }
}

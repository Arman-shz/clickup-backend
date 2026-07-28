package ir.arman.api.error;

import ir.arman.api.dto.ErrorResponse;
import jakarta.annotation.Priority;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.List;

/**
 * Maps bean-validation failures to the spec's BadRequest body.
 *
 * <p>Quarkus ships its own violation mapper that emits a different shape, so this one
 * is given an explicit high priority (lower number wins in JAX-RS) to take precedence.
 * The individual violations are surfaced in {@code errors}, which the spec's
 * ErrorResponse schema declares as an array of strings.
 */
@Provider
@Priority(1)
public class ValidationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        List<String> errors = exception.getConstraintViolations().stream()
                .map(ValidationExceptionMapper::describe)
                .sorted()
                .toList();

        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ErrorResponse.of(ApiMessages.BAD_REQUEST, errors))
                .build();
    }

    /** Renders "field: message", trimming the method/parameter prefix off the path. */
    private static String describe(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        String field = lastDot >= 0 ? path.substring(lastDot + 1) : path;
        return field.isBlank() ? violation.getMessage() : field + ": " + violation.getMessage();
    }
}

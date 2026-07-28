package ir.arman.api.error;

import ir.arman.api.dto.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.hibernate.exception.ConstraintViolationException;

/**
 * A unique constraint rejected the write. Without this the client sees a 500, which
 * describes a broken server rather than a request asking for something already taken.
 *
 * <p>Note the import: this is Hibernate's ConstraintViolationException -- a database
 * constraint that fired during flush -- not the identically named
 * {@code jakarta.validation} one handled by {@link ValidationExceptionMapper}. They are
 * unrelated types and this file must never be "tidied" to use the other one.
 *
 * <p>This is a net, not the primary check. POST /api/auth/register looks the student id
 * up first and answers 409 without ever reaching the database, which is both cheaper and
 * able to say precisely what collided. What is left for this mapper is the narrow race
 * where two identical registrations pass that lookup at the same moment, plus the
 * unique email that PUT /api/users/me can collide with once phase 3 exists.
 */
@Provider
public class DataConflictExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        // The constraint name distinguishes the cases the API can name precisely.
        String constraint = exception.getConstraintName();
        String message = constraint != null && constraint.contains("student_id")
                ? ApiMessages.STUDENT_ID_TAKEN
                : ApiMessages.CONFLICT;

        return Response.status(Response.Status.CONFLICT)
                .entity(ErrorResponse.of(message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}

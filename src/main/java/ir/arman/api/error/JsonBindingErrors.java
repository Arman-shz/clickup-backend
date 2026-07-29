package ir.arman.api.error;

import com.fasterxml.jackson.databind.JsonMappingException;

import java.util.List;

/**
 * Turns a Jackson binding failure into the one-line "field: reason" that the spec's
 * ErrorResponse carries in {@code errors} -- the same shape
 * {@link ValidationExceptionMapper} produces, so a client parsing that array does not
 * need two readers.
 *
 * <p>Shared because the same failure arrives by two routes: some Jackson exceptions
 * reach JAX-RS intact, and the rest are wrapped in a WebApplicationException by the
 * message body reader before any mapper is consulted. See
 * {@link JsonBindingExceptionMapper} and {@link BadRequestBodyMapper}.
 */
final class JsonBindingErrors {

    private JsonBindingErrors() {
    }

    static String describe(JsonMappingException exception) {
        String field = fieldOf(exception);
        String reason = reasonOf(exception);
        return field == null ? reason : field + ": " + reason;
    }

    /** The last named property in the path, e.g. `theme`. Null for a whole-body failure. */
    private static String fieldOf(JsonMappingException exception) {
        List<JsonMappingException.Reference> path = exception.getPath();
        for (int i = path.size() - 1; i >= 0; i--) {
            String name = path.get(i).getFieldName();
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    /**
     * The enums throw IllegalArgumentException from their {@code @JsonCreator}, and that
     * message lists the values that were allowed -- far more use to a client than
     * Jackson's wrapper around it. Everything else falls back to a flat statement,
     * deliberately: Jackson's own text quotes internal class names and fragments of the
     * payload.
     */
    private static String reasonOf(JsonMappingException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof IllegalArgumentException && cause.getMessage() != null) {
            return cause.getMessage();
        }
        return "invalid value";
    }
}

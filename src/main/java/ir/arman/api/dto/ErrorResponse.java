package ir.arman.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The spec's ErrorResponse schema (swagger.yaml, components/schemas/ErrorResponse).
 *
 * <p>{@code errors} is omitted from the JSON when null so that the 401 and 404 bodies
 * match the spec's examples exactly, which carry only {@code success} and {@code message}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@RegisterForReflection
public record ErrorResponse(boolean success, String message, List<String> errors) {

    public static ErrorResponse of(String message) {
        return new ErrorResponse(false, message, null);
    }

    public static ErrorResponse of(String message, List<String> errors) {
        return new ErrorResponse(false, message, errors);
    }
}

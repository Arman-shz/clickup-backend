package ir.arman.api.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import ir.arman.logging.LogLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * The body of POST /api/logs (swagger.yaml, paths./api/logs.post).
 *
 * <p>{@code level} is the enum rather than a String, so an undocumented value is refused
 * during binding with a 400 naming the accepted set. Writing it as text instead would put
 * a value into the file that nothing later reading it could rely on.
 *
 * <p>{@code message} is capped, and the cap is load-bearing rather than tidy: an entry
 * becomes one line in a file that is appended to concurrently, and short lines are what
 * keeps a single append atomic. The spec sets no limit; 4096 characters is well past any
 * real client message and well under any figure that would make a line risky.
 *
 * <p>{@code context} is free-form by the spec ({@code additionalProperties: true}) and is
 * kept that way -- the whole point is that the frontend can attach whatever it had at the
 * time. It is bounded on the way out instead: {@link ir.arman.logging.ClientLogWriter}
 * replaces an oversized context with a marker rather than refusing the entry, because
 * losing the error report to save the details would be the wrong trade.
 */
@RegisterForReflection
public record ClientLogRequest(

        @NotNull
        LogLevel level,

        @NotBlank
        @Size(max = 4096)
        String message,

        Map<String, Object> context) {
}

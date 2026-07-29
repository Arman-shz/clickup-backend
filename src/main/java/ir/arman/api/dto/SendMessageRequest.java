package ir.arman.api.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of POST /api/chat/messages (swagger.yaml, paths./api/chat/messages.post).
 *
 * <p>Two properties, and the spec marks only {@code content} required. Everything else on
 * a ChatMessage -- the id, the sender, their name and avatar, the timestamp -- is stamped
 * by the server, so a message cannot be sent under someone else's name or backdated.
 *
 * <p>{@code content} is {@code @NotBlank} rather than {@code @NotNull}: the column is TEXT
 * NOT NULL, and a message of three spaces is an empty message that would still push into
 * everyone's open stream. No maximum -- TEXT has none, and the spec sets none.
 *
 * <p>{@code fileUrl} is capped at the width of its VARCHAR(1024) column so an over-long
 * value is a 400 with the offending field named, not a 500 from the driver. It is a
 * reference to something POST /api/upload returned; nothing here fetches or checks it,
 * because that route does not exist yet (phase 9).
 */
@RegisterForReflection
public record SendMessageRequest(

        @NotBlank
        String content,

        @Size(max = 1024)
        String fileUrl) {
}

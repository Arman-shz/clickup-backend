package ir.arman.api.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import ir.arman.domain.ChatMessage;

import java.time.format.DateTimeFormatter;

/**
 * The spec's ChatMessage schema (swagger.yaml, components/schemas/ChatMessage).
 *
 * <p>The sender's name and avatar are properties of the message rather than a reference to
 * the account, and that is what the column layout says too: chat_messages snapshots both.
 * A message therefore keeps the name its author had when they sent it, and the stream can
 * render an event without a lookup per message.
 *
 * <p>{@code timestamp} is the entity's {@code sentAt} under the name the spec gives it --
 * the column is {@code sent_at} because `timestamp` is a SQL type keyword. It is rendered
 * as an explicit ISO-8601 instant, matching the spec's SSE example
 * ({@code "2026-07-28T10:00:00Z"}) and the shape Project.createdAt already uses.
 *
 * <p>This one record serves all three routes: the history, the 201 and the SSE event. That
 * is deliberate -- a live message and the same message read back an hour later must not
 * differ in shape, or a client would need two parsers for one thing.
 */
@RegisterForReflection
public record ChatMessageResponse(
        String id,
        String senderId,
        String senderName,
        String senderAvatar,
        String content,
        String fileUrl,
        String timestamp) {

    public static ChatMessageResponse of(ChatMessage message) {
        return new ChatMessageResponse(
                message.id,
                message.senderId,
                message.senderName,
                message.senderAvatar,
                message.content,
                message.fileUrl,
                message.sentAt == null
                        ? null
                        : DateTimeFormatter.ISO_INSTANT.format(message.sentAt));
    }
}

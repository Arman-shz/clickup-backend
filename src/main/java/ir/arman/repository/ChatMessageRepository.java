package ir.arman.repository;

import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import ir.arman.domain.ChatMessage;
import ir.arman.domain.User;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class ChatMessageRepository extends PrefixedIdRepository<ChatMessage> {

    @Override
    protected String idPrefix() {
        return ChatMessage.ID_PREFIX;
    }

    @Override
    protected String idSequence() {
        return ChatMessage.ID_SEQUENCE;
    }

    /** GET /api/chat/messages -- a transcript, so oldest first. */
    public Uni<List<ChatMessage>> listHistory() {
        return listAll(Sort.by("sentAt"));
    }

    /**
     * POST /api/chat/messages. The request body carries only content and fileUrl; the
     * sender is stamped from the caller, and their name and avatar are copied in so the
     * SSE stream can emit a message without looking the account up again.
     */
    public Uni<ChatMessage> send(ChatMessage message, User sender) {
        return nextId().flatMap(id -> {
            message.id = id;
            message.senderId = sender.id;
            message.senderName = sender.name;
            message.senderAvatar = sender.avatar;
            message.sentAt = now();
            return persist(message);
        });
    }
}

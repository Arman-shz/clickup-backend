package ir.arman.chat;

import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import ir.arman.api.dto.ChatMessageResponse;
import ir.arman.api.dto.SendMessageRequest;
import ir.arman.domain.ChatMessage;
import ir.arman.repository.ChatMessageRepository;
import ir.arman.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The live half of the chat: one process-wide stream every connected SSE client shares.
 *
 * <p>It sits outside ChatResource on purpose, and the reason is transaction ordering.
 * {@code @WithTransaction} commits when the Uni it wraps completes, so anything emitted
 * from inside that pipeline is broadcast <em>before</em> the row is committed -- a message
 * that then failed to save would already have arrived on everyone's screen, and no later
 * GET would ever show it. Because {@link #store} is a call across a CDI boundary, the
 * resource can chain the publish onto the Uni it returns and be certain the write landed.
 *
 * <p>Nothing here is persisted or replayed. A client that connects sees what is said from
 * then on; the transcript it missed is what GET /api/chat/messages is for. The subscriber
 * count is not tracked either -- publishing to a stream with no listeners is a no-op, and
 * a message is stored whether or not anyone is watching.
 *
 * <p>This is a single-process broadcast. Two instances of this application behind a load
 * balancer would each only reach the clients connected to them, which is worth knowing
 * before it is scaled out: making it work would need the message to travel through
 * Postgres LISTEN/NOTIFY or a broker rather than a field on a bean.
 */
@ApplicationScoped
public class ChatHub {

    /**
     * How far behind a slow client may fall before its stream is failed. Reconnecting and
     * re-fetching the history is a recoverable state; silently dropping messages out of
     * the middle of a conversation is not, so the buffer fails rather than discards.
     */
    private static final int SLOW_CLIENT_BUFFER = 256;

    /** Hot by nature: it carries what is being said now, not a replayable log. */
    private final BroadcastProcessor<ChatMessageResponse> live = BroadcastProcessor.create();

    @Inject
    ChatMessageRepository messages;

    @Inject
    UserRepository users;

    /**
     * Writes the message and commits.
     *
     * <p>The author is loaded rather than taken from the token alone because the row
     * snapshots their name and avatar, and only the account has those.
     *
     * @return the stored message, or {@code null} if the token names an account that no
     *         longer exists -- the case /api/users/me answers with a 401.
     */
    @WithTransaction
    public Uni<ChatMessage> store(String senderId, SendMessageRequest request) {
        return users.findById(senderId).flatMap(sender -> {
            if (sender == null) {
                return Uni.createFrom().nullItem();
            }

            ChatMessage message = new ChatMessage();
            message.content = request.content().strip();
            message.fileUrl = request.fileUrl() == null || request.fileUrl().isBlank()
                    ? null
                    : request.fileUrl().strip();

            return messages.send(message, sender);
        });
    }

    /** Hands a committed message to every open stream. Safe to call from any thread. */
    public void publish(ChatMessageResponse message) {
        live.onNext(message);
    }

    /** One subscription per connected client, each with its own overflow buffer. */
    public Multi<ChatMessageResponse> stream() {
        return live.onOverflow().buffer(SLOW_CLIENT_BUFFER);
    }
}

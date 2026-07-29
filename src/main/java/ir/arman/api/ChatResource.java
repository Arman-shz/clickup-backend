package ir.arman.api;

import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import ir.arman.api.dto.ChatMessageResponse;
import ir.arman.api.dto.ErrorResponse;
import ir.arman.api.dto.SendMessageRequest;
import ir.arman.api.error.ApiMessages;
import ir.arman.chat.ChatHub;
import ir.arman.repository.ChatMessageRepository;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.List;

/**
 * /api/chat -- the team chat: a transcript, a send route, and a live stream.
 *
 * <p>The chat is one room. The spec declares no channel, no recipient and no membership on
 * ChatMessage, so every account reads and writes the same conversation.
 *
 * <p><strong>The stream is authenticated like everything else.</strong> The spec omits the
 * {@code security} block on /api/chat/stream -- the only route outside /api/auth,
 * /api/health and /api/logs that does -- but it serves the same messages
 * /api/chat/messages refuses without a token, so leaving it open would have made that 401
 * decorative. Recorded decision; swagger.yaml has been corrected to match. The cost is
 * real and belongs to the frontend: the browser's native {@code EventSource} cannot send
 * an Authorization header, so the client needs a fetch-based SSE reader instead.
 */
@Path("/api/chat")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class ChatResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    ChatMessageRepository messages;

    @Inject
    ChatHub hub;

    /**
     * GET /api/chat/messages -- oldest first. A transcript is read downwards, and it is
     * the order the sent_at index in changelog 001 serves.
     */
    @GET
    @Path("/messages")
    @WithSession
    public Uni<List<ChatMessageResponse>> history() {
        return messages.listHistory()
                .map(found -> found.stream().map(ChatMessageResponse::of).toList());
    }

    /**
     * POST /api/chat/messages -- 201 with the stored message, which is also pushed to
     * every open stream.
     *
     * <p>The publish is chained onto the Uni {@link ChatHub#store} returns rather than
     * done inside it, so it happens after the commit: no client is shown a message that
     * the database then refused.
     */
    @POST
    @Path("/messages")
    public Uni<Response> send(@Valid SendMessageRequest request) {
        if (request == null) {
            return Uni.createFrom().item(badRequest());
        }

        return hub.store(currentUserId(), request).map(stored -> {
            if (stored == null) {
                return unauthorized();
            }

            ChatMessageResponse sent = ChatMessageResponse.of(stored);
            hub.publish(sent);

            return Response.status(Response.Status.CREATED).entity(sent).build();
        });
    }

    /**
     * GET /api/chat/stream -- server-sent events, one JSON message per event, exactly the
     * shape the spec's example shows and the same shape the other two routes return.
     *
     * <p>The Multi never completes on its own, which is the point: the response stays open
     * until the client disconnects or the server stops. Only messages sent after the
     * connection opened arrive here -- a client that wants what it missed asks
     * /api/chat/messages for it.
     */
    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<ChatMessageResponse> stream() {
        return hub.stream();
    }

    /** The `upn` claim: the id of whoever holds the token. */
    private String currentUserId() {
        return identity.getPrincipal().getName();
    }

    /** components/responses/Unauthorized. */
    private static Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ErrorResponse.of(ApiMessages.UNAUTHORIZED))
                .build();
    }

    /** components/responses/BadRequest, for a requestBody the spec marks required. */
    private static Response badRequest() {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ErrorResponse.of(ApiMessages.BAD_REQUEST))
                .build();
    }
}

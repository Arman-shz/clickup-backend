package ir.arman.api;

import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import ir.arman.api.dto.ErrorResponse;
import ir.arman.api.dto.UserProfileResponse;
import ir.arman.api.error.ApiMessages;
import ir.arman.repository.UserRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * /api/users/me -- the caller's own profile.
 *
 * <p>There is no {@code /api/users/{id}} in the spec, and this resource does not invent
 * one. Every route here reads its subject from the bearer token, so one account can never
 * address another: the id is not an input.
 */
@Path("/api/users/me")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class UserResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    UserRepository users;

    /**
     * GET /api/users/me -- the full UserProfile for whoever holds the token.
     *
     * <p>The spec documents 200 and 401 only. A token that parses but names an account
     * that no longer exists gets the 401: the credential is well-formed but identifies
     * nobody, and inventing a 404 would document a response the spec does not have.
     */
    @GET
    @WithSession
    public Uni<Response> me() {
        return users.findById(currentUserId())
                .map(user -> user == null
                        ? unauthorized()
                        : Response.ok(UserProfileResponse.of(user)).build());
    }

    /**
     * The {@code upn} claim, which {@code TokenService} sets to the user id. Reading the
     * subject from the token rather than from the request body is what makes these routes
     * safe to leave unparameterised.
     */
    private String currentUserId() {
        return identity.getPrincipal().getName();
    }

    /** components/responses/Unauthorized. */
    private static Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ErrorResponse.of(ApiMessages.UNAUTHORIZED))
                .build();
    }
}

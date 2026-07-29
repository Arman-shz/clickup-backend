package ir.arman.api;

import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import ir.arman.api.dto.ErrorResponse;
import ir.arman.api.dto.UpdateUserProfileRequest;
import ir.arman.api.dto.UserProfileResponse;
import ir.arman.api.error.ApiMessages;
import ir.arman.domain.User;
import ir.arman.repository.UserRepository;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
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
     * PUT /api/users/me -- edits the profile and the display settings.
     *
     * <p>A merge, not a replacement: a property the request does not mention is left as
     * it was. See {@link UpdateUserProfileRequest} for why, and for what that costs.
     *
     * <p>The spec documents only 200 for this route, which cannot be the whole story --
     * it is behind BearerAuth, so 401 exists, an invalid body is the 400 every other
     * write returns, and {@code email} is UNIQUE, so an address another account already
     * holds has to be refused. All three are now in swagger.yaml.
     *
     * <p>Nothing here can change {@code role}, {@code studentId} or {@code status}: they
     * are not in the request schema, so there is no field to send. Identity and privilege
     * are not self-service.
     */
    @PUT
    @WithTransaction
    public Uni<Response> update(@Valid UpdateUserProfileRequest request) {
        return users.findById(currentUserId()).flatMap(user -> {
            if (user == null) {
                return Uni.createFrom().item(unauthorized());
            }
            if (request == null) {
                return Uni.createFrom().item(Response.ok(UserProfileResponse.of(user)).build());
            }

            String email = strip(request.email());
            // Checked before the write so the response can say what collided. The
            // constraint still backs this up -- see DataConflictExceptionMapper -- for
            // the race where two accounts claim the same address at once.
            return emailIsTaken(email, user).flatMap(taken -> {
                if (taken) {
                    return Uni.createFrom().item(Response.status(Response.Status.CONFLICT)
                            .entity(ErrorResponse.of(ApiMessages.EMAIL_TAKEN))
                            .build());
                }
                apply(request, email, user);
                // No explicit persist: `user` is a managed entity inside
                // @WithTransaction, so the changes flush when the transaction commits.
                return Uni.createFrom().item(
                        Response.ok(UserProfileResponse.of(user)).build());
            });
        });
    }

    /** False when the address is absent or already this account's own. */
    private Uni<Boolean> emailIsTaken(String email, User user) {
        if (email == null || email.equals(user.email)) {
            return Uni.createFrom().item(false);
        }
        return users.findByEmail(email).map(owner -> owner != null);
    }

    /** Copies across only the properties the request actually carried. */
    private static void apply(UpdateUserProfileRequest request, String email, User user) {
        if (request.name() != null) {
            user.name = request.name().strip();
        }
        if (email != null) {
            user.email = email;
        }
        if (request.avatar() != null) {
            user.avatar = request.avatar().strip();
        }
        if (request.theme() != null) {
            user.theme = request.theme();
        }
        if (request.language() != null) {
            user.language = request.language();
        }
        if (request.notificationsEnabled() != null) {
            user.notificationsEnabled = request.notificationsEnabled();
        }
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
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

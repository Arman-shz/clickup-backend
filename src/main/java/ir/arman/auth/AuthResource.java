package ir.arman.auth;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.credential.PasswordCredential;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.smallrye.mutiny.Uni;
import ir.arman.api.dto.ErrorResponse;
import ir.arman.api.dto.LoginRequest;
import ir.arman.api.dto.RefreshRequest;
import ir.arman.api.dto.RefreshResponse;
import ir.arman.api.dto.RegisterRequest;
import ir.arman.api.error.ApiMessages;
import ir.arman.domain.Language;
import ir.arman.domain.Role;
import ir.arman.domain.Theme;
import ir.arman.domain.User;
import ir.arman.repository.RefreshTokenRepository;
import ir.arman.repository.UserRepository;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.UUID;

@Path("/api/auth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    /** TeamMember.status for a usable account. The spec gives the field no enum. */
    private static final String STATUS_ACTIVE = "active";

    @Inject
    UserRepository users;

    @Inject
    PasswordService passwords;

    @Inject
    TokenService tokens;

    @Inject
    IdentityProviderManager identityProviderManager;

    @Inject
    RefreshTokenRepository refreshTokens;

    /**
     * POST /api/auth/register -- creates an account and signs it in.
     *
     * <p>This endpoint is not in the original swagger; it was added because there was no
     * other way for a user to come into existence. Without it, production ran the schema
     * migrations without the demo seed and had no accounts at all, so /api/auth/login had
     * nobody to authenticate.
     *
     * <p>Every new account is a {@code student}. Promotion to {@code admin} is not
     * something a registration form gets to decide; nothing in the API grants it, so it
     * is a deliberate act against the database.
     *
     * <p>The whole method is one transaction: the row and its first refresh token are
     * inserted together, and a failure anywhere leaves neither behind.
     */
    @POST
    @Path("/register")
    @WithTransaction
    public Uni<Response> register(@Valid RegisterRequest request) {
        String studentId = request.studentId().strip();
        String name = request.name().strip();

        return users.findByStudentId(studentId).flatMap(existing -> {
            if (existing != null) {
                return Uni.createFrom().item(Response.status(Response.Status.CONFLICT)
                        .entity(ErrorResponse.of(ApiMessages.STUDENT_ID_TAKEN))
                        .build());
            }

            // Hashing before the insert, not after: an account is never stored without a
            // usable password, and a rejected registration costs no write at all.
            return passwords.hash(request.password())
                    .map(passwordHash -> newAccount(studentId, name, passwordHash))
                    .flatMap(users::create)
                    .flatMap(tokens::issueFor)
                    .map(session -> Response.status(Response.Status.CREATED)
                            .entity(session)
                            .build());
        });
    }

    /**
     * POST /api/auth/login -- exchanges credentials for a token pair.
     *
     * <p>The password is never compared here. {@code IdentityProviderManager} hands the
     * request to the security-jpa provider, which finds the row by {@code @Username} and
     * checks the candidate against the {@code @Password} column in Modular Crypt Format.
     * That keeps the comparison constant-time and in one place, and means this method
     * cannot accidentally use {@code equals} on a hash.
     *
     * <p>Every failure -- unknown student id, wrong password, deactivated account --
     * returns the same 401 with the same message. Telling the three apart would let an
     * unauthenticated caller enumerate which student ids exist.
     */
    @POST
    @Path("/login")
    public Uni<Response> login(@Valid LoginRequest request) {
        String studentId = request.studentId().strip();

        return identityProviderManager
                .authenticate(new UsernamePasswordAuthenticationRequest(
                        studentId, new PasswordCredential(request.password().toCharArray())))
                // The transaction opens *after* authentication, and this method carries no
                // @WithTransaction, on purpose. The identity provider runs its own session,
                // and work resumed afterwards is no longer inside a transaction opened
                // before it -- an insert there is simply dropped. Silently: the refresh
                // token is a UUID minted in Java, so the response still looked correct
                // while no row existed and every later refresh would have failed with 401.
                .flatMap(identity -> Panache.withTransaction(() -> users.findByStudentId(studentId)
                        .flatMap(user -> {
                            // The spec gives TeamMember.status no enum and no endpoint
                            // that sets it, so it can only be changed against the database
                            // directly -- by an operator deliberately shutting an account
                            // down. Honouring it is the only reading under which that act
                            // does anything at all.
                            if (!STATUS_ACTIVE.equals(user.status)) {
                                return Uni.createFrom().item(badCredentials());
                            }
                            return tokens.issueFor(user)
                                    .map(session -> Response.ok(session).build());
                        })))
                // Recovered here rather than left to AuthenticationFailedExceptionMapper:
                // that mapper emits the generic "دسترسی غیرمجاز" body, while the spec
                // documents a different, more specific message for this route's 401.
                .onFailure(AuthenticationFailedException.class)
                .recoverWithItem(AuthResource::badCredentials);
    }

    /**
     * POST /api/auth/refresh -- trades a live refresh token for a new pair.
     *
     * <p>Rotating, not renewing. The presented token is revoked in the same transaction
     * that issues its replacement, so each one is usable exactly once. That is what makes
     * a stolen refresh token a bounded problem: the thief and the real client cannot both
     * use it, and whichever loses is locked out immediately and visibly.
     *
     * <p>Unknown, malformed, expired, revoked, or belonging to an account that has since
     * been deactivated all produce the same 401. The route documents no other failure.
     */
    @POST
    @Path("/refresh")
    public Uni<Response> refresh(RefreshRequest request) {
        UUID presented = parseToken(request);
        if (presented == null) {
            return Uni.createFrom().item(unauthorized());
        }

        Instant now = Instant.now();
        return Panache.withTransaction(() -> refreshTokens.findUsable(presented, now)
                .flatMap(live -> {
                    if (live == null) {
                        return Uni.createFrom().item(unauthorized());
                    }
                    // revoke() reports whether it changed a row. A false here means
                    // another request revoked the same token between the read and this
                    // write, so this one lost the race and must not also issue a pair.
                    return refreshTokens.revoke(presented, now).flatMap(revoked -> revoked
                            ? issueSuccessor(live.userId)
                            : Uni.createFrom().item(unauthorized()));
                }));
    }

    private Uni<Response> issueSuccessor(String userId) {
        return users.findById(userId).flatMap(user -> {
            // The account can have been deactivated since the token was issued. Access
            // tokens outlive that by up to their lifespan; refresh must not extend it.
            if (user == null || !STATUS_ACTIVE.equals(user.status)) {
                return Uni.createFrom().item(unauthorized());
            }
            return tokens.issueFor(user)
                    .map(session -> Response.ok(RefreshResponse.of(session)).build());
        });
    }

    /** Null for anything that is not a UUID, including a missing body or field. */
    private static UUID parseToken(RefreshRequest request) {
        if (request == null || request.refreshToken() == null || request.refreshToken().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(request.refreshToken().strip());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** components/responses/Unauthorized, which is what this route's 401 references. */
    private static Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ErrorResponse.of(ApiMessages.UNAUTHORIZED))
                .build();
    }

    /** paths./api/auth/login.post.responses.401, verbatim. */
    private static Response badCredentials() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ErrorResponse.of(ApiMessages.BAD_CREDENTIALS))
                .build();
    }

    /**
     * The defaults the registration form does not ask about. They match the column
     * defaults in changelog 001, restated here because Hibernate sends every column on
     * insert and so the database defaults never actually apply.
     */
    private static User newAccount(String studentId, String name, String passwordHash) {
        User user = new User();
        user.studentId = studentId;
        user.name = name;
        user.passwordHash = passwordHash;
        user.role = Role.STUDENT;
        user.theme = Theme.LIGHT;
        user.language = Language.FA;
        user.notificationsEnabled = true;
        user.status = STATUS_ACTIVE;
        // email and avatar stay null until PUT /api/users/me sets them.
        return user;
    }
}

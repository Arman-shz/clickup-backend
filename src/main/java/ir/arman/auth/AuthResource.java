package ir.arman.auth;

import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import ir.arman.api.dto.ErrorResponse;
import ir.arman.api.dto.RegisterRequest;
import ir.arman.api.error.ApiMessages;
import ir.arman.domain.Language;
import ir.arman.domain.Role;
import ir.arman.domain.Theme;
import ir.arman.domain.User;
import ir.arman.repository.UserRepository;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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

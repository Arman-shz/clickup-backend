package ir.arman.auth;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import ir.arman.domain.Language;
import ir.arman.domain.Role;
import ir.arman.domain.Theme;
import ir.arman.domain.User;
import ir.arman.repository.UserRepository;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Creates the first administrator from the environment (task 11.6).
 *
 * <h2>Why an application has to do this at all</h2>
 *
 * <p>A production database starts with no accounts -- the demo seed is filtered out by the
 * `production` Liquibase context -- and {@code POST /api/auth/register} hard-codes the
 * {@code student} role, deliberately: nothing reachable over HTTP is allowed to decide who
 * is an administrator. Those two facts together mean a fresh deployment has no admin and no
 * way to acquire one, and {@code POST /api/logs} takes nobody else (D9). Somebody has to
 * make the first one, and the only actor present before anyone has logged in is startup.
 *
 * <h2>It creates; it does not maintain</h2>
 *
 * <p>If an account with that student id already exists, this leaves it completely alone --
 * no password reset, no role change, not even a check that it is still an admin. That is
 * the whole difference between a bootstrap and a back door. Re-applying the configured
 * password on every start would silently undo a deliberate change made against the
 * database, and would turn a variable sitting in a compose file into a permanent way in
 * for anyone who can read the deployment's environment.
 *
 * <h2>Half-configured is an error, not a default</h2>
 *
 * <p>Neither variable set means the deployment is not asking for an admin, and nothing
 * happens. One of the two set means somebody meant to and did not finish, and the
 * application stops rather than starting with an administrator that was asked for and
 * silently not created.
 *
 * <h2>The cost, stated</h2>
 *
 * <p>The password is a plain environment variable. It is visible to anything that can read
 * the container's environment -- {@code docker inspect}, a shell in the container, a
 * process listing on some platforms -- and it stays visible for the life of the
 * deployment, long after the account it created stopped needing it. Change the password
 * through the API once you are in, and treat the variable as spent.
 */
@ApplicationScoped
public class AdminBootstrap {

    private static final Logger LOG = Logger.getLogger(AdminBootstrap.class);

    @ConfigProperty(name = "app.bootstrap.admin.student-id")
    Optional<String> configuredStudentId;

    @ConfigProperty(name = "app.bootstrap.admin.password")
    Optional<String> configuredPassword;

    @ConfigProperty(name = "app.bootstrap.admin.name")
    String configuredName;

    @Inject
    UserRepository users;

    @Inject
    PasswordService passwords;

    /**
     * Runs after {@link KeyMaterial}, and after the Liquibase migration -- which is not an
     * observer at all but a runtime init step, and so is already finished by the time any
     * {@link StartupEvent} observer is called. The schema this writes into therefore
     * exists.
     */
    void createFirstAdmin(
            @Observes @Priority(Interceptor.Priority.APPLICATION + 100) StartupEvent event) {

        String studentId = value(configuredStudentId);
        String password = value(configuredPassword);

        if (studentId == null && password == null) {
            LOG.debug("No bootstrap administrator configured");
            return;
        }
        if (studentId == null || password == null) {
            throw new IllegalStateException(
                    "app.bootstrap.admin needs both halves, or neither. Only "
                            + (studentId == null ? "the password" : "the student id")
                            + " was set, so no administrator would have been created and"
                            + " nothing would have said so.");
        }

        try {
            // Hibernate Reactive keeps its session in a Vert.x duplicated context, and
            // startup is not on one -- a plain subscribe here fails with "No current Vertx
            // context found". This runs the work on a context and blocks until it is done,
            // which is what startup wants anyway: the application must not begin serving
            // while the account it was told to create is still half made.
            VertxContextSupport.subscribeAndAwait(
                    () -> Panache.withTransaction(() -> create(studentId, password)));
        } catch (RuntimeException alreadyUnchecked) {
            throw alreadyUnchecked;
        } catch (Throwable failure) {
            throw new IllegalStateException(
                    "the bootstrap administrator could not be created", failure);
        }
    }

    private Uni<Void> create(String studentId, String password) {
        return users.findByStudentId(studentId).flatMap(existing -> {
            if (existing != null) {
                LOG.infof("Bootstrap administrator %s already exists (%s, role %s);"
                        + " leaving it untouched", studentId, existing.id, existing.role);
                return Uni.createFrom().voidItem();
            }

            return passwords.hash(password)
                    .map(hash -> account(studentId, hash))
                    .flatMap(users::create)
                    .invoke(created -> LOG.warnf(
                            "Created the bootstrap administrator %s (%s). Change its"
                                    + " password through the API and stop passing"
                                    + " ADMIN_PASSWORD to this deployment.",
                            studentId, created.id))
                    .replaceWithVoid();
        });
    }

    /**
     * The same defaults registration applies, with the one difference that is the point of
     * this class: {@link Role#ADMIN}.
     */
    private User account(String studentId, String passwordHash) {
        User user = new User();
        user.studentId = studentId;
        user.name = configuredName;
        user.passwordHash = passwordHash;
        user.role = Role.ADMIN;
        user.theme = Theme.LIGHT;
        user.language = Language.FA;
        user.notificationsEnabled = true;
        user.status = User.STATUS_ACTIVE;
        return user;
    }

    /** Treats blank as absent: an empty variable is how compose spells "not set". */
    private static String value(Optional<String> configured) {
        return configured.map(String::strip).filter(text -> !text.isEmpty()).orElse(null);
    }
}

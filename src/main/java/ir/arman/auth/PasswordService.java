package ir.arman.auth;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.function.Supplier;

/**
 * Task 2.1: password hashing, following the security-jpa guide -- {@code BcryptUtil}
 * writing Modular Crypt Format, which is the format {@code @Password} reads by default.
 *
 * <p>Hashing only. Verification is not here and must not be added: the security-jpa
 * provider does it during authentication, and a second implementation of "is this the
 * right password" is exactly the kind of thing that drifts.
 *
 * <p>The work is deliberately pushed onto the worker pool. bcrypt is slow *by design* --
 * at the default cost of 10 a single hash is tens of milliseconds of solid CPU -- and
 * this application is fully reactive, so running it inline would park an event-loop
 * thread and stall every other request sharing it. That is the cost a password hash is
 * meant to impose on an attacker, not on the server.
 */
@ApplicationScoped
public class PasswordService {

    /** Produces a Modular Crypt Format hash: {@code $2a$10$<salt><digest>}. */
    public Uni<String> hash(String plainText) {
        return offload(() -> BcryptUtil.bcryptHash(plainText));
    }

    /**
     * Runs {@code work} on a worker thread and hands the result back on the very Vert.x
     * context that subscribed.
     *
     * <p>The trip back matters as much as the trip out. Hibernate Reactive keeps the
     * current {@code Mutiny.Session} in the duplicated context's local storage, so a
     * caller that hashes a password mid-transaction and then continues on the worker
     * thread would find no session and fail with "No current Mutiny.Session found".
     * Re-entering the original context puts the session back within reach.
     */
    private <T> Uni<T> offload(Supplier<T> work) {
        return Uni.createFrom().deferred(() -> {
            Context context = Vertx.currentContext();
            Uni<T> offloaded = Uni.createFrom().item(work)
                    .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());

            // No Vert.x context means no session to preserve -- a plain unit test, say.
            return context == null
                    ? offloaded
                    : offloaded.emitOn(command -> context.runOnContext(ignored -> command.run()));
        });
    }
}

package ir.arman.health;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * A single reactive round-trip to Postgres, shared by the MicroProfile readiness check
 * and by the spec's own /api/health endpoint so the two can never disagree.
 */
@ApplicationScoped
public class DatabaseProbe {

    private static final Logger LOG = Logger.getLogger(DatabaseProbe.class);

    @Inject
    Pool pool;

    /**
     * @return true when Postgres answers a trivial query, false on any failure.
     *         Never fails the returned {@link Uni} -- callers report status, not errors.
     */
    public Uni<Boolean> isConnected() {
        return pool.query("SELECT 1").execute()
                .map(rows -> true)
                .onFailure().recoverWithItem(failure -> {
                    LOG.warnf(failure, "Database health probe failed");
                    return false;
                });
    }
}

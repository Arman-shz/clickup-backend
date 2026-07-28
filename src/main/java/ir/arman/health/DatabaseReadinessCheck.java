package ir.arman.health;

import io.smallrye.health.api.AsyncHealthCheck;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness check surfaced at /q/health/ready and /q/health.
 *
 * <p>Implements SmallRye's {@link AsyncHealthCheck} rather than the synchronous
 * {@code HealthCheck}: the plain interface would force the reactive probe to be blocked
 * on, which is exactly what a fully reactive stack must not do on an event-loop thread.
 */
@Readiness
@ApplicationScoped
public class DatabaseReadinessCheck implements AsyncHealthCheck {

    static final String NAME = "Database connection health check";

    @Inject
    DatabaseProbe probe;

    @Override
    public Uni<HealthCheckResponse> call() {
        return probe.isConnected()
                .map(connected -> HealthCheckResponse.named(NAME)
                        .status(connected)
                        .withData("database", connected ? "connected" : "disconnected")
                        .build());
    }
}

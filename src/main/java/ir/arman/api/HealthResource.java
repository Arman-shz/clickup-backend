package ir.arman.api;

import io.smallrye.mutiny.Uni;
import ir.arman.api.dto.HealthResponse;
import ir.arman.health.DatabaseProbe;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * GET /api/health -- the spec's own health endpoint (tag: System).
 *
 * <p>This is deliberately separate from SmallRye's /q/health/* endpoints. Those keep
 * their standard MicroProfile payload for container probes; this one returns the
 * {status, timestamp, database} shape the spec documents for API clients. Both read the
 * same {@link DatabaseProbe}, so they cannot report different things.
 *
 * <p>Unauthenticated, matching the spec: /api/health is the only path besides login and
 * refresh with no BearerAuth requirement.
 */
@Path("/api/health")
public class HealthResource {

    /** The spec's example is "2026-07-28T08:12:00.000Z" -- UTC, always 3 decimals. */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Inject
    DatabaseProbe probe;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> health() {
        return probe.isConnected().map(connected -> {
            HealthResponse body = new HealthResponse(
                    connected ? HealthResponse.STATUS_OK : HealthResponse.STATUS_ERROR,
                    TIMESTAMP.format(Instant.now()),
                    connected ? HealthResponse.DB_CONNECTED : HealthResponse.DB_DISCONNECTED);

            // The spec documents only the 200 case ("وضعیت سرور نرمال است"). A failed
            // database is not that case, so it answers 503 with the same body shape --
            // reporting 200 while the database is unreachable would make this endpoint
            // useless as a probe.
            Response.Status status = connected
                    ? Response.Status.OK
                    : Response.Status.SERVICE_UNAVAILABLE;

            return Response.status(status).entity(body).build();
        });
    }
}

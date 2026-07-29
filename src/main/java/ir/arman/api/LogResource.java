package ir.arman.api;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import ir.arman.api.dto.ClientLogRequest;
import ir.arman.api.dto.ErrorResponse;
import ir.arman.api.dto.SuccessMessageResponse;
import ir.arman.api.error.ApiMessages;
import ir.arman.domain.Role;
import ir.arman.logging.ClientLogWriter;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * POST /api/logs -- the frontend writing to the server's log files (decision D7).
 *
 * <p>Entries land in {@code app.log}, and in {@code error.log} as well when the level is
 * {@code error}, exactly as the spec says. Where that directory is, and what keeps it from
 * growing forever, is {@link ClientLogWriter}.
 *
 * <p><strong>Admin only.</strong> The spec declares no {@code security} block on this
 * route at all, which made it an open write endpoint; swagger.yaml has been corrected.
 * Decision D9, and its cost is worth being blunt about because it is not what central
 * client logging usually means: a student's browser can no longer report anything. Every
 * client-side crash, every failed render, and in particular everything that goes wrong
 * before or instead of a successful sign-in, is now a 401 or a 403 and is never written
 * down. What the route records is what an admin's own session ran into.
 *
 * <p>Second consequence, which belongs to production rather than to this class:
 * registration hard-codes {@code student} and the seed changesets are filtered out of
 * production, so a production database has no admin account and therefore nobody who can
 * use this route. That is task 11.6.
 *
 * <p>The response is 200 whenever the entry was accepted, including when the file could
 * not be written. A frontend can do nothing with "the disk is full", and turning that into
 * a failed request would make every error report produce a second error report.
 */
@Path("/api/logs")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Role.ADMIN_VALUE)
public class LogResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    ClientLogWriter writer;

    @POST
    public Uni<Response> record(@Valid ClientLogRequest request) {
        if (request == null) {
            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of(ApiMessages.BAD_REQUEST))
                    .build());
        }

        return writer.write(request, identity.getPrincipal().getName())
                .replaceWith(() -> Response.ok(SuccessMessageResponse.ok()).build());
    }
}

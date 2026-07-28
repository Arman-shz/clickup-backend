package ir.arman.auth;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * A resource that exists only on the test classpath.
 *
 * <p>Task 2.5 is bearer-token wiring and RBAC, and there is nothing to hang it on yet:
 * the first endpoint the spec protects arrives in phase 3. Rather than leave the wiring
 * unverified until then, or invent a production endpoint to test it with, the probe
 * lives here -- it is compiled into no artifact and served by no deployment.
 *
 * <p>What it pins down is that a token issued by {@code TokenService} is accepted by the
 * bearer mechanism, that {@code groups} reaches {@code @RolesAllowed}, and that a request
 * without a usable token is refused with the body the spec documents.
 */
@Path("/test-only/protected")
@Produces(MediaType.TEXT_PLAIN)
public class ProtectedProbeResource {

    @Inject
    SecurityIdentity identity;

    /** Any authenticated caller, whatever their role. */
    @GET
    @Path("/any")
    @Authenticated
    public String anyAuthenticated() {
        return identity.getPrincipal().getName();
    }

    @GET
    @Path("/admin")
    @RolesAllowed("admin")
    public String adminsOnly() {
        return "admin: " + identity.getPrincipal().getName();
    }

    @GET
    @Path("/student")
    @RolesAllowed("student")
    public String studentsOnly() {
        return "student: " + identity.getPrincipal().getName();
    }

    /** No annotation: proves the auth routes are reachable without a token by default. */
    @GET
    @Path("/open")
    public String open() {
        return "open";
    }
}

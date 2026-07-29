package ir.arman.api;

import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import ir.arman.api.dto.TeamMemberResponse;
import ir.arman.repository.UserRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * /api/members -- the team directory.
 *
 * <p>Read-only, and that is the spec's shape rather than an omission: there is no POST,
 * PUT or DELETE on this path anywhere in the document. Members arrive by registering
 * themselves through /api/auth/register, and the invite flow that would otherwise create
 * one here was deliberately removed from the spec. So there is nothing to write.
 *
 * <p>Every account is listed, including inactive ones. The spec puts {@code status} in
 * TeamMember, which is only worth sending if the caller is meant to see members who are
 * not active and render them differently -- filtering them out here would make the
 * property pointless and hide people who still own tasks.
 */
@Path("/api/members")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class MemberResource {

    @Inject
    UserRepository users;

    /**
     * GET /api/members -- ordered by name, collated by Postgres with the ICU `fa` locale
     * so Persian names sort the way a Persian reader expects rather than by code point.
     */
    @GET
    @WithSession
    public Uni<List<TeamMemberResponse>> list() {
        return users.listMembers()
                .map(found -> found.stream().map(TeamMemberResponse::of).toList());
    }
}

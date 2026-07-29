package ir.arman.api;

import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import ir.arman.api.dto.CreateProjectRequest;
import ir.arman.api.dto.ErrorResponse;
import ir.arman.api.dto.ProjectResponse;
import ir.arman.api.dto.SuccessMessageResponse;
import ir.arman.api.dto.SyncProjectsRequest;
import ir.arman.api.error.ApiMessages;
import ir.arman.domain.Project;
import ir.arman.repository.ProjectRepository;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * /api/projects.
 *
 * <p>Projects are not owned. The spec's Project schema has no owner field, GET is not
 * scoped to the caller, and there is no membership concept anywhere in the document --
 * so every authenticated account sees and edits the same set. That is the spec's design,
 * not an omission here; adding ownership would change documented behaviour and quietly
 * break the frontend's project list.
 */
@Path("/api/projects")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class ProjectResource {

    @Inject
    ProjectRepository projects;

    /** GET /api/projects -- oldest first, the only ordering the schema can support. */
    @GET
    @WithSession
    public Uni<List<ProjectResponse>> list() {
        return projects.listOrdered()
                .map(found -> found.stream().map(ProjectResponse::of).toList());
    }

    /** POST /api/projects -- 201 with the stored project, id and createdAt included. */
    @POST
    @WithTransaction
    public Uni<Response> create(@Valid CreateProjectRequest request) {
        if (request == null) {
            return Uni.createFrom().item(badRequest());
        }

        Project project = new Project();
        overwrite(project, request);
        return projects.create(project)
                .map(stored -> Response.status(Response.Status.CREATED)
                        .entity(ProjectResponse.of(stored))
                        .build());
    }

    /**
     * PUT /api/projects/{id} -- replaces the project's contents; 404 if there is none.
     *
     * <p>A replacement, not a merge: the body is CreateProjectRequest, the same schema
     * POST uses, so an absent description or colour means the same thing on both routes,
     * which is null. Sending only a title therefore clears the rest. PUT /api/users/me
     * merges instead, and the difference comes from the schemas -- that one makes every
     * property optional, this one requires a title, so a caller here is already sending
     * the whole resource.
     *
     * <p>{@code id} and {@code createdAt} are not in the request schema and cannot be
     * changed. A project keeps the identity and the age it was created with.
     */
    @PUT
    @Path("/{id}")
    @WithTransaction
    public Uni<Response> update(@PathParam("id") String id, @Valid CreateProjectRequest request) {
        if (request == null) {
            return Uni.createFrom().item(badRequest());
        }

        return projects.findById(id).map(project -> {
            if (project == null) {
                return notFound();
            }
            overwrite(project, request);
            // No explicit persist: the entity is managed inside @WithTransaction and
            // flushes on commit.
            return Response.ok(ProjectResponse.of(project)).build();
        });
    }

    /**
     * DELETE /api/projects/{id} -- 200 with the spec's success message, or 404.
     *
     * <p>This takes the project's tasks with it. The foreign key is ON DELETE CASCADE
     * (changelog 001, a deliberate departure recorded there), so a project cannot be
     * deleted out from under its tasks and leave them pointing at nothing.
     */
    @DELETE
    @Path("/{id}")
    @WithTransaction
    public Uni<Response> delete(@PathParam("id") String id) {
        return projects.deleteById(id)
                .map(deleted -> deleted
                        ? Response.ok(SuccessMessageResponse.ok()).build()
                        : notFound());
    }

    /**
     * POST /api/projects/sync -- bulk upsert by id. Decision D5.
     *
     * <p>An id that exists is updated; one that does not is created under the id the
     * client sent. <strong>Nothing is ever deleted</strong>, including projects the
     * payload does not mention. The alternative reading -- the payload is the complete
     * server state, so remove the rest -- is the only one under which the two sides truly
     * converge, but it makes a single request from a client holding a stale or
     * half-loaded list destroy projects and, through the cascade, every task on them,
     * while the response says nothing beyond "success". That is too much damage for an
     * endpoint whose semantics the spec never wrote down.
     *
     * <p>The consequence, stated rather than hidden: a project deleted on the client
     * comes back on the next sync, because the server has no way to tell "deleted" from
     * "not loaded". Deleting is DELETE /api/projects/{id}'s job.
     *
     * <p>Creating under a client-chosen id is what makes this idempotent -- syncing the
     * same offline project twice updates it the second time instead of duplicating it.
     */
    @POST
    @Path("/sync")
    @WithTransaction
    public Uni<Response> sync(@Valid SyncProjectsRequest request) {
        if (request == null || request.projects() == null) {
            return Uni.createFrom().item(badRequest());
        }

        List<SyncProjectsRequest.Project> incoming = request.projects();
        String duplicate = firstDuplicateId(incoming);
        if (duplicate != null) {
            // Applying both and letting the last win would make the result depend on
            // array order for a request that never said which entry it meant.
            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of(ApiMessages.BAD_REQUEST,
                            List.of("projects: duplicate id " + duplicate)))
                    .build());
        }

        Set<String> ids = incoming.stream()
                .map(SyncProjectsRequest.Project::id)
                .collect(Collectors.toSet());

        return projects.findAllById(ids).flatMap(existing -> {
            Map<String, Project> byId = new HashMap<>();
            existing.forEach(project -> byId.put(project.id, project));

            Uni<Void> work = Uni.createFrom().voidItem();
            for (SyncProjectsRequest.Project element : incoming) {
                Project stored = byId.get(element.id());
                if (stored != null) {
                    overwrite(stored, element);
                } else {
                    Project fresh = new Project();
                    fresh.id = element.id();
                    overwrite(fresh, element);
                    // Chained rather than run in parallel: they share one session.
                    work = work.flatMap(ignored ->
                            projects.insertWithClientId(fresh).replaceWithVoid());
                }
            }
            return work.map(ignored -> Response.ok(SuccessMessageResponse.ok()).build());
        });
    }

    /** Null when every id is distinct. */
    private static String firstDuplicateId(List<SyncProjectsRequest.Project> incoming) {
        Set<String> seen = new HashSet<>();
        for (SyncProjectsRequest.Project element : incoming) {
            if (!seen.add(element.id())) {
                return element.id();
            }
        }
        return null;
    }

    private static void overwrite(Project project, CreateProjectRequest request) {
        project.title = request.title().strip();
        project.description = strip(request.description());
        project.color = strip(request.color());
        project.icon = strip(request.icon());
    }

    private static void overwrite(Project project, SyncProjectsRequest.Project element) {
        project.title = element.title().strip();
        project.description = strip(element.description());
        project.color = strip(element.color());
        project.icon = strip(element.icon());
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    /** components/responses/NotFound. */
    private static Response notFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ErrorResponse.of(ApiMessages.NOT_FOUND))
                .build();
    }

    /** components/responses/BadRequest, for a requestBody the spec marks required. */
    private static Response badRequest() {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ErrorResponse.of(ApiMessages.BAD_REQUEST))
                .build();
    }
}

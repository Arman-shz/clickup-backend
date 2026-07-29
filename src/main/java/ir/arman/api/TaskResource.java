package ir.arman.api;

import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import ir.arman.api.dto.CreateTaskRequest;
import ir.arman.api.dto.ErrorResponse;
import ir.arman.api.dto.SuccessMessageResponse;
import ir.arman.api.dto.TaskResponse;
import ir.arman.api.error.ApiMessages;
import ir.arman.domain.Task;
import ir.arman.domain.TaskStatus;
import ir.arman.repository.ProjectRepository;
import ir.arman.repository.TaskRepository;
import ir.arman.repository.UserRepository;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * /api/tasks.
 *
 * <p>Like projects, tasks are not owned: the spec's Task has no creator field, GET is not
 * scoped to the caller, and {@code assignees} is a list of who should do the work, not of
 * who may edit it. Any authenticated account can therefore change any task, which is what
 * the document describes.
 *
 * <p>Two of the columns behind this resource are foreign keys -- {@code project_id} and
 * every row in {@code task_assignees} -- and both are checked here before the write. The
 * database would refuse an unknown id anyway, but as a constraint violation, which is a
 * 500 for what is plainly a bad request.
 */
@Path("/api/tasks")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class TaskResource {

    @Inject
    TaskRepository tasks;

    @Inject
    ProjectRepository projects;

    @Inject
    UserRepository users;

    /**
     * GET /api/tasks -- the whole list, or the part matching the spec's two filters.
     *
     * <p>Both parameters are optional and independent, and an absent one means "do not
     * filter" rather than "match null". An empty one -- {@code ?projectId=&status=} --
     * means the same, because that is what a form or a query-string builder sends for a
     * filter the user has not set, and reading it as a literal value would answer with an
     * empty list instead of everything.
     *
     * <p>{@code status} is read as a String and converted here rather than declared as
     * the enum: JAX-RS turns a query parameter it cannot convert into a 404, and a
     * misspelt filter is a bad request, not a missing endpoint. An unknown project id is
     * not an error -- it is a filter that matches nothing, so the answer is {@code []}.
     */
    @GET
    @WithSession
    public Uni<Response> list(@QueryParam("projectId") String projectId,
                              @QueryParam("status") String status) {
        TaskStatus wanted;
        try {
            wanted = isBlank(status) ? null : TaskStatus.from(status.strip());
        } catch (IllegalArgumentException undocumentedValue) {
            return Uni.createFrom().item(
                    badRequest(List.of("status: " + undocumentedValue.getMessage())));
        }

        return tasks.listFiltered(isBlank(projectId) ? null : projectId.strip(), wanted)
                .map(found -> Response.ok(found.stream().map(TaskResponse::of).toList()).build());
    }

    /** POST /api/tasks -- 201 with the stored task, id included. */
    @POST
    @WithTransaction
    public Uni<Response> create(@Valid CreateTaskRequest request) {
        if (request == null) {
            return Uni.createFrom().item(badRequest(null));
        }

        return problemsWith(request).flatMap(problems -> {
            if (!problems.isEmpty()) {
                return Uni.createFrom().item(badRequest(problems));
            }
            Task task = new Task();
            overwrite(task, request);
            return tasks.create(task)
                    .map(stored -> Response.status(Response.Status.CREATED)
                            .entity(TaskResponse.of(stored))
                            .build());
        });
    }

    /**
     * PUT /api/tasks/{id} -- replaces the task's contents; 404 if there is none.
     *
     * <p>A replacement, not a merge, for the same reason PUT /api/projects/{id} is one:
     * the body is CreateTaskRequest, the schema POST uses, so an absent property means
     * null on both routes. The spec's summary for this route is "edit or change status",
     * and a status change is done by sending the task back with a different status --
     * <strong>the whole task</strong>. A request carrying only projectId, title and status
     * succeeds with a 200 and leaves the task with no description, no priority, no due
     * date and nobody assigned to it.
     *
     * <p>Moving a task to a different project is allowed: {@code projectId} is part of the
     * body the client replaces, and the new one is checked to exist like any other.
     *
     * <p>The body is validated before the task is looked up, so a bad request against a
     * task that does not exist answers 400 rather than 404. Nothing is written either way.
     */
    @PUT
    @Path("/{id}")
    @WithTransaction
    public Uni<Response> update(@PathParam("id") String id, @Valid CreateTaskRequest request) {
        if (request == null) {
            return Uni.createFrom().item(badRequest(null));
        }

        return problemsWith(request).flatMap(problems -> {
            if (!problems.isEmpty()) {
                return Uni.createFrom().item(badRequest(problems));
            }
            // With assignees fetched: the collection is lazy, and replacing it means
            // reading it first.
            return tasks.findByIdWithAssignees(id).map(task -> {
                if (task == null) {
                    return notFound();
                }
                overwrite(task, request);
                // No explicit persist: the entity is managed inside @WithTransaction.
                return Response.ok(TaskResponse.of(task)).build();
            });
        });
    }

    /**
     * DELETE /api/tasks/{id} -- 200 with the spec's success message, or 404.
     *
     * <p>The task's rows in {@code task_assignees} go with it. That is the database's
     * ON DELETE CASCADE rather than Hibernate's doing: Panache deletes by id with a bulk
     * statement, which never visits the element collection.
     */
    @DELETE
    @Path("/{id}")
    @WithTransaction
    public Uni<Response> delete(@PathParam("id") String id) {
        return tasks.deleteById(id)
                .map(deleted -> deleted
                        ? Response.ok(SuccessMessageResponse.ok()).build()
                        : notFound());
    }

    /**
     * Everything wrong with a body that already passed bean validation: a due date that
     * is not a date, and ids that refer to nothing. Empty when the request is good.
     *
     * <p>All of it is collected rather than returned at the first failure, so a client
     * fixing a form is told about every field at once.
     */
    private Uni<List<String>> problemsWith(CreateTaskRequest request) {
        List<String> problems = new ArrayList<>();

        String malformedDueDate = dueDateProblem(request.dueDate());
        if (malformedDueDate != null) {
            problems.add(malformedDueDate);
        }

        String projectId = request.projectId().strip();
        Set<String> assignees = assigneesOf(request);

        return projects.existsById(projectId)
                .flatMap(exists -> {
                    if (!exists) {
                        problems.add("projectId: no project with id " + projectId);
                    }
                    return users.findExistingIds(assignees);
                })
                .map(known -> {
                    assignees.stream()
                            .filter(id -> !known.contains(id))
                            .forEach(id -> problems.add("assignees: no user with id " + id));
                    return problems;
                });
    }

    /** Null when the due date is absent or a valid calendar date. */
    private static String dueDateProblem(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            LocalDate.parse(raw.strip());
            return null;
        } catch (DateTimeParseException notADate) {
            // Names the shape rather than echoing java.time's message, which talks about
            // parse indexes and would tell a frontend developer nothing.
            return "dueDate: must be a date of the form YYYY-MM-DD, was: " + raw;
        }
    }

    /** The distinct, non-blank assignee ids of a request, in the order they were sent. */
    private static Set<String> assigneesOf(CreateTaskRequest request) {
        Set<String> ids = new LinkedHashSet<>();
        if (request.assignees() != null) {
            request.assignees().stream()
                    .filter(id -> !isBlank(id))
                    .map(String::strip)
                    .forEach(ids::add);
        }
        return ids;
    }

    private static void overwrite(Task task, CreateTaskRequest request) {
        task.projectId = request.projectId().strip();
        task.title = request.title().strip();
        task.description = strip(request.description());
        // The column is NOT NULL DEFAULT 'todo' and the spec's Task requires a status
        // while CreateTaskRequest does not, so an omitted one starts at the first column.
        task.status = request.status() == null ? TaskStatus.TODO : request.status();
        task.priority = request.priority();
        task.dueDate = isBlank(request.dueDate()) ? null : LocalDate.parse(request.dueDate().strip());
        // Replaced wholesale, including when the request omits the property entirely.
        Set<String> replacement = assigneesOf(request);
        task.assignees.clear();
        task.assignees.addAll(replacement);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    /** components/responses/BadRequest, with the offending fields when there are any. */
    private static Response badRequest(List<String> problems) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(problems == null || problems.isEmpty()
                        ? ErrorResponse.of(ApiMessages.BAD_REQUEST)
                        : ErrorResponse.of(ApiMessages.BAD_REQUEST, problems))
                .build();
    }
}

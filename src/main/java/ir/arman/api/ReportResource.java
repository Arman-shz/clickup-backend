package ir.arman.api;

import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import ir.arman.api.dto.CreateReportRequest;
import ir.arman.api.dto.ErrorResponse;
import ir.arman.api.dto.WeeklyReportResponse;
import ir.arman.api.error.ApiMessages;
import ir.arman.domain.Role;
import ir.arman.domain.WeeklyReport;
import ir.arman.repository.UserRepository;
import ir.arman.repository.WeeklyReportRepository;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.RoundingMode;
import java.util.List;

/**
 * /api/reports -- weekly work reports.
 *
 * <p>This is the one resource in the spec with an owner. Project and Task carry no
 * creator and are shared by every account; WeeklyReport carries {@code userId}, and what
 * it holds -- hours worked, difficulties -- is a personal record rather than shared
 * material. So the history is scoped: an admin sees every report, anyone else sees their
 * own. The spec does not say this, and it is the recorded decision behind the route.
 *
 * <p>The consequence, stated rather than buried: {@code GET /api/reports} answers
 * differently for two callers at the same URL, so the response cannot be cached across
 * accounts. And because registration hard-codes {@code student}, the only admins are the
 * ones the seed created -- no account can become one through this API.
 */
@Path("/api/reports")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class ReportResource {

    /** The fractional digits of weekly_reports.hours_worked, NUMERIC(6,2). */
    private static final int HOURS_SCALE = 2;

    @Inject
    SecurityIdentity identity;

    @Inject
    WeeklyReportRepository reports;

    @Inject
    UserRepository users;

    /**
     * GET /api/reports -- newest first, which is the order the index in changelog 001 is
     * built for and the only one a history is read in.
     */
    @GET
    @WithSession
    public Uni<List<WeeklyReportResponse>> history() {
        Uni<List<WeeklyReport>> found = identity.hasRole(Role.ADMIN.value())
                ? reports.listHistory()
                : reports.listHistoryFor(currentUserId());

        return found.map(list -> list.stream().map(WeeklyReportResponse::of).toList());
    }

    /**
     * POST /api/reports -- 201 with the filed report.
     *
     * <p>The author is read from the token, never from the body. The account is loaded
     * rather than trusted from the claim alone because the report snapshots the author's
     * name, and only the row has it.
     *
     * <p>Nothing prevents two reports covering the same week: the spec declares no
     * uniqueness on {@code weekTitle}, the titles are free text a person writes, and
     * refusing the second would make a corrected report impossible to file.
     */
    @POST
    @WithTransaction
    public Uni<Response> submit(@Valid CreateReportRequest request) {
        if (request == null) {
            return Uni.createFrom().item(badRequest());
        }

        return users.findById(currentUserId()).flatMap(author -> {
            if (author == null) {
                // A well-formed token naming an account that no longer exists, the same
                // case GET /api/users/me answers with a 401.
                return Uni.createFrom().item(unauthorized());
            }

            WeeklyReport report = new WeeklyReport();
            report.weekTitle = request.weekTitle().strip();
            // Scaled to the column before it is echoed back. Otherwise the 201 renders
            // whatever the client sent -- `42` for an integer literal -- while a later
            // GET renders `42.00` off the NUMERIC(6,2) column, and the same value would
            // reach the client in two shapes depending on how it was asked for.
            // UNNECESSARY cannot throw here: @Digits already refused a third decimal.
            report.hoursWorked = request.hoursWorked().setScale(HOURS_SCALE, RoundingMode.UNNECESSARY);
            report.tasksCompleted = request.tasksCompleted();
            report.achievements = strip(request.achievements());
            report.challenges = strip(request.challenges());
            report.nextWeekPlan = strip(request.nextWeekPlan());

            return reports.submit(report, author)
                    .map(filed -> Response.status(Response.Status.CREATED)
                            .entity(WeeklyReportResponse.of(filed))
                            .build());
        });
    }

    /** The `upn` claim: the id of whoever holds the token. */
    private String currentUserId() {
        return identity.getPrincipal().getName();
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    /** components/responses/Unauthorized. */
    private static Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ErrorResponse.of(ApiMessages.UNAUTHORIZED))
                .build();
    }

    /** components/responses/BadRequest, for a requestBody the spec marks required. */
    private static Response badRequest() {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ErrorResponse.of(ApiMessages.BAD_REQUEST))
                .build();
    }
}

package ir.arman.repository;

import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import ir.arman.domain.User;
import ir.arman.domain.WeeklyReport;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class WeeklyReportRepository extends PrefixedIdRepository<WeeklyReport> {

    @Override
    protected String idPrefix() {
        return WeeklyReport.ID_PREFIX;
    }

    @Override
    protected String idSequence() {
        return WeeklyReport.ID_SEQUENCE;
    }

    /** GET /api/reports for an admin -- history, so newest first. */
    public Uni<List<WeeklyReport>> listHistory() {
        return listAll(Sort.by("submittedAt").descending());
    }

    /**
     * GET /api/reports for everyone else. A weekly report is a personal record, so a
     * non-admin reads only what they filed themselves -- the decision recorded on
     * {@code ReportResource}.
     */
    public Uni<List<WeeklyReport>> listHistoryFor(String userId) {
        return list("userId", Sort.by("submittedAt").descending(), userId);
    }

    /**
     * POST /api/reports. The author is stamped here rather than taken from the request
     * body: CreateReportRequest carries no userId, userName or submittedAt.
     */
    public Uni<WeeklyReport> submit(WeeklyReport report, User author) {
        return nextId().flatMap(id -> {
            report.id = id;
            report.userId = author.id;
            report.userName = author.name;
            report.submittedAt = now();
            return persist(report);
        });
    }
}

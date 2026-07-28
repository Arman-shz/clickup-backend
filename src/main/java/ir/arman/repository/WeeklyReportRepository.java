package ir.arman.repository;

import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import ir.arman.domain.User;
import ir.arman.domain.WeeklyReport;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
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

    /** GET /api/reports -- history, so newest first. */
    public Uni<List<WeeklyReport>> listHistory() {
        return listAll(Sort.by("submittedAt").descending());
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
            report.submittedAt = Instant.now();
            return persist(report);
        });
    }
}

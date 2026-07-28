package ir.arman.domain;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A submitted weekly report. POST /api/reports stamps userId, userName and submittedAt
 * from the caller rather than accepting them, so CreateReportRequest omits all three.
 */
@Entity
@Table(name = "weekly_reports")
public class WeeklyReport extends PanacheEntityBase {

    public static final String ID_PREFIX = "rep_";
    public static final String ID_SEQUENCE = "weekly_reports_id_seq";

    @Id
    public String id;

    @Column(name = "user_id", nullable = false)
    public String userId;

    /**
     * Snapshotted, not joined. A filed report is a historical record and keeps the name
     * it was submitted under even if the account is renamed afterwards.
     */
    @Column(name = "user_name", nullable = false)
    public String userName;

    @Column(name = "week_title", nullable = false)
    public String weekTitle;

    /** NUMERIC in the database: the spec types it `number`, and halves are normal. */
    @Column(name = "hours_worked", nullable = false)
    public BigDecimal hoursWorked;

    @Column(name = "tasks_completed", nullable = false)
    public int tasksCompleted;

    public String achievements;

    public String challenges;

    @Column(name = "next_week_plan")
    public String nextWeekPlan;

    @Column(name = "submitted_at", nullable = false)
    public Instant submittedAt;
}

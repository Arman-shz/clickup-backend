package ir.arman.domain;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A task.
 *
 * projectId is a plain column rather than a @ManyToOne: the spec's Task carries a bare
 * `projectId` string and never nests the project, so mapping an association would only
 * add a load the API has no use for. The foreign key is still enforced -- by the
 * database, which is where the changelog declares it.
 */
@Entity
@Table(name = "tasks")
public class Task extends PanacheEntityBase {

    public static final String ID_PREFIX = "task_";
    public static final String ID_SEQUENCE = "tasks_id_seq";

    @Id
    public String id;

    @Column(name = "project_id", nullable = false)
    public String projectId;

    @Column(nullable = false)
    public String title;

    public String description;

    @Column(nullable = false)
    public TaskStatus status;

    public TaskPriority priority;

    /**
     * Task.assignees is an array of user ids, so it gets its own table rather than a
     * column. An element collection of ids keeps the loaded shape identical to the
     * serialised one -- resolving these to User rows would fetch names nothing reads.
     *
     * Lazy on purpose: eager loading would issue one extra query per row on
     * GET /api/tasks. The repository join-fetches instead.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "task_assignees", joinColumns = @JoinColumn(name = "task_id"))
    @Column(name = "user_id", nullable = false)
    public Set<String> assignees = new LinkedHashSet<>();

    /** The spec types dueDate as a string but examples it as a plain date. */
    @Column(name = "due_date")
    public LocalDate dueDate;
}

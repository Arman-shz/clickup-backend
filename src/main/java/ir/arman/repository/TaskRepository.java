package ir.arman.repository;

import io.smallrye.mutiny.Uni;
import ir.arman.domain.Task;
import ir.arman.domain.TaskStatus;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class TaskRepository extends PrefixedIdRepository<Task> {

    @Override
    protected String idPrefix() {
        return Task.ID_PREFIX;
    }

    @Override
    protected String idSequence() {
        return Task.ID_SEQUENCE;
    }

    /**
     * GET /api/tasks, with the spec's two optional filters. Either may be null, and
     * null means "do not filter" rather than "match null".
     *
     * Assignees are join-fetched so that serialising the result does not trigger a
     * query per task. The join multiplies rows, hence DISTINCT.
     *
     * Ordered by due date because the ids are strings: sorting on them puts task_10
     * ahead of task_2. Tasks with no due date come last.
     *
     * Not called `list`: Panache already inherits list(String, Parameters), and a null
     * second argument would make the call ambiguous.
     */
    public Uni<List<Task>> listFiltered(String projectId, TaskStatus status) {
        StringBuilder query = new StringBuilder(
                "SELECT DISTINCT t FROM Task t LEFT JOIN FETCH t.assignees");
        List<String> conditions = new ArrayList<>();
        Map<String, Object> parameters = new HashMap<>();

        if (projectId != null) {
            conditions.add("t.projectId = :projectId");
            parameters.put("projectId", projectId);
        }
        if (status != null) {
            conditions.add("t.status = :status");
            parameters.put("status", status);
        }
        if (!conditions.isEmpty()) {
            query.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        query.append(" ORDER BY t.dueDate NULLS LAST, t.id");

        return find(query.toString(), parameters).list();
    }

    /** Single task with its assignees already loaded, for the PUT and DELETE routes. */
    public Uni<Task> findByIdWithAssignees(String id) {
        return find("SELECT DISTINCT t FROM Task t LEFT JOIN FETCH t.assignees WHERE t.id = :id",
                Map.of("id", id))
                .firstResult();
    }

    public Uni<Task> create(Task task) {
        return nextId().flatMap(id -> {
            task.id = id;
            return persist(task);
        });
    }
}

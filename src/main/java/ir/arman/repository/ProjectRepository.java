package ir.arman.repository;

import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import ir.arman.domain.Project;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class ProjectRepository extends PrefixedIdRepository<Project> {

    @Override
    protected String idPrefix() {
        return Project.ID_PREFIX;
    }

    @Override
    protected String idSequence() {
        return Project.ID_SEQUENCE;
    }

    /** GET /api/projects. Oldest first: createdAt is the only ordering the spec carries. */
    public Uni<List<Project>> listOrdered() {
        return listAll(Sort.by("createdAt"));
    }

    public Uni<Project> create(Project project) {
        return nextId().flatMap(id -> {
            project.id = id;
            project.createdAt = Instant.now();
            return persist(project);
        });
    }
}

package ir.arman.repository;

import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import ir.arman.domain.Project;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class ProjectRepository extends PrefixedIdRepository<Project> {

    /** An id of the shape this repository's own sequence produces: `proj_12`. */
    private static final Pattern GENERATED_ID =
            Pattern.compile(Pattern.quote(Project.ID_PREFIX) + "(\\d{1,18})");

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

    /** The rows POST /api/projects/sync is updating, in one query rather than one each. */
    public Uni<List<Project>> findAllById(Collection<String> ids) {
        return ids.isEmpty()
                ? Uni.createFrom().item(List.of())
                : list("id in ?1", ids);
    }

    /**
     * Inserts a project under an id the client chose, which only /sync does.
     *
     * <p>That id can collide with one the sequence has not reached yet: a client that
     * syncs `proj_900` today makes every future POST /api/projects fail once the sequence
     * counts that high, as a primary key violation from an endpoint that did nothing
     * wrong. So the sequence is pushed past any `proj_<n>` this endpoint stores. It only
     * ever moves forward -- GREATEST against the current value -- so a sync of old ids
     * cannot rewind it and start handing out ids that are already taken.
     */
    public Uni<Project> insertWithClientId(Project project) {
        project.createdAt = Instant.now();
        return persist(project).flatMap(persisted -> reserve(project.id).replaceWith(persisted));
    }

    private Uni<Void> reserve(String id) {
        Matcher generated = GENERATED_ID.matcher(id);
        if (!generated.matches()) {
            // Not a shape this sequence can ever produce, so it cannot collide with one.
            return Uni.createFrom().voidItem();
        }

        long number;
        try {
            number = Long.parseLong(generated.group(1));
        } catch (NumberFormatException tooLongToBeANumber) {
            return Uni.createFrom().voidItem();
        }

        return getSession()
                .flatMap(session -> session.createNativeQuery(
                                "SELECT setval(CAST(:sequence AS regclass), GREATEST("
                                        + ":number, COALESCE((SELECT last_value FROM pg_sequences"
                                        + " WHERE schemaname = current_schema()"
                                        + " AND sequencename = :name), 1)))", Long.class)
                        .setParameter("sequence", idSequence())
                        .setParameter("name", idSequence())
                        .setParameter("number", number)
                        .getSingleResult())
                .replaceWithVoid();
    }
}

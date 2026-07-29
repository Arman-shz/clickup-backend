package ir.arman.repository;

import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import ir.arman.domain.User;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class UserRepository extends PrefixedIdRepository<User> {

    @Override
    protected String idPrefix() {
        return User.ID_PREFIX;
    }

    @Override
    protected String idSequence() {
        return User.ID_SEQUENCE;
    }

    /** The lookup behind POST /api/auth/login. */
    public Uni<User> findByStudentId(String studentId) {
        return find("studentId", studentId).firstResult();
    }

    /**
     * The collision check behind PUT /api/users/me. `email` is UNIQUE, so an address
     * already claimed by another account has to be refused with a 409 rather than left
     * to fail as a constraint violation.
     */
    public Uni<User> findByEmail(String email) {
        return find("email", email).firstResult();
    }

    /**
     * Which of these ids exist -- the assignee check on POST and PUT /api/tasks.
     *
     * `task_assignees.user_id` is a foreign key, so an unknown assignee would otherwise
     * fail as a constraint violation and be reported as a 500 for what is a bad request.
     * One query for the whole list, and only the ids: loading User rows here would pull
     * every password hash in the payload into the session for nothing.
     */
    public Uni<Set<String>> findExistingIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return Uni.createFrom().item(Set.of());
        }
        return getSession()
                .flatMap(session -> session
                        .createQuery("SELECT u.id FROM User u WHERE u.id IN :ids", String.class)
                        .setParameter("ids", ids)
                        .getResultList())
                .map(Set::copyOf);
    }

    /**
     * GET /api/members. Ordered by name, which the database collates with the ICU `fa`
     * locale -- ordering here rather than in the caller keeps that collation in play.
     */
    public Uni<List<User>> listMembers() {
        return listAll(Sort.by("name"));
    }

    public Uni<User> create(User user) {
        return nextId().flatMap(id -> {
            user.id = id;
            user.createdAt = now();
            return persist(user);
        });
    }
}

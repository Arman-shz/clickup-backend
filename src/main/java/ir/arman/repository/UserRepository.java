package ir.arman.repository;

import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import ir.arman.domain.User;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;

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
     * GET /api/members. Ordered by name, which the database collates with the ICU `fa`
     * locale -- ordering here rather than in the caller keeps that collation in play.
     */
    public Uni<List<User>> listMembers() {
        return listAll(Sort.by("name"));
    }

    public Uni<User> create(User user) {
        return nextId().flatMap(id -> {
            user.id = id;
            user.createdAt = Instant.now();
            return persist(user);
        });
    }
}

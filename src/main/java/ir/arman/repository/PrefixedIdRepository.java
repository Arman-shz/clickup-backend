package ir.arman.repository;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Base for the entities whose ids are prefixed strings drawn from a per-entity sequence
 * -- usr_101, proj_1, msg_201, the ids the spec shows literally.
 *
 * Hibernate cannot generate these: @GeneratedValue produces numbers, and the reactive
 * identifier-generator SPI is internal. Drawing the number explicitly costs one extra
 * round trip on insert and keeps the mechanism visible.
 *
 * The draw runs on the caller's session, so it joins the surrounding transaction. That
 * does not make it roll back with one -- sequences never do -- so a failed insert
 * leaves a gap in the ids. Gaps are expected; nothing here relies on them being dense.
 */
public abstract class PrefixedIdRepository<T> implements PanacheRepositoryBase<T, String> {

    /** The prefix the generated ids carry, e.g. {@code usr_}. */
    protected abstract String idPrefix();

    /** The Postgres sequence supplying the numeric part. */
    protected abstract String idSequence();

    /**
     * The clock, at the precision the database keeps.
     *
     * <p>{@code Instant.now()} resolves to nanoseconds; every TIMESTAMPTZ column stores
     * microseconds. Stamping a row with the raw value means the 201 echoes back digits
     * that no later read will ever return -- {@code ...546791146Z} on creation and
     * {@code ...546791Z} from then on, the same instant in two shapes. Truncating here
     * makes the response the client is given equal to the row that was written.
     */
    protected static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    protected Uni<String> nextId() {
        // The sequence name is bound as a parameter and cast, rather than concatenated
        // into the statement.
        return getSession()
                .flatMap(session -> session
                        .createNativeQuery("SELECT nextval(CAST(:sequence AS regclass))", Long.class)
                        .setParameter("sequence", idSequence())
                        .getSingleResult())
                .map(value -> idPrefix() + value);
    }
}

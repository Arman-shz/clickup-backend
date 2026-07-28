package ir.arman.domain;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A project. The spec's Project has no owner field and GET /api/projects is not scoped
 * to the caller, so there is deliberately no owner column here.
 */
@Entity
@Table(name = "projects")
public class Project extends PanacheEntityBase {

    public static final String ID_PREFIX = "proj_";
    public static final String ID_SEQUENCE = "projects_id_seq";

    @Id
    public String id;

    @Column(nullable = false)
    public String title;

    public String description;

    public String color;

    public String icon;

    /** Serialised as the spec's `createdAt` string; stored as a real timestamp. */
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}

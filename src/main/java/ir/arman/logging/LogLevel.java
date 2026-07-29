package ir.arman.logging;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code level} of a client log entry (swagger.yaml, paths./api/logs.post) --
 * {@code info}, {@code warn} or {@code error}.
 *
 * <p>An enum rather than a String for the same reason TaskStatus is one: an unknown value
 * is refused during binding with a 400 that names the accepted set, rather than being
 * written into a log file that something later greps for {@code "level":"error"}. It also
 * decides which files the entry lands in.
 *
 * <p>It lives here rather than in {@code ir.arman.domain} because nothing persists it --
 * no column, no converter. It exists between the request body and a line of text.
 *
 * <p>Which is exactly why it carries {@code @RegisterForReflection} while
 * {@link ir.arman.domain.Role} does not: the domain enums are reached by the Hibernate
 * extension through the entities that persist them, and this one is reached by nothing.
 * Jackson binds it through the {@code @JsonCreator} below, reflectively. See
 * {@code ir.arman.api.dto.package-info} for the phase-8 version of this same gap.
 */
@RegisterForReflection
public enum LogLevel {

    INFO("info"),
    WARN("warn"),
    ERROR("error");

    private final String value;

    LogLevel(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static LogLevel from(String value) {
        for (LogLevel level : values()) {
            if (level.value.equals(value)) {
                return level;
            }
        }
        throw new IllegalArgumentException("must be one of [info, warn, error], was: " + value);
    }
}

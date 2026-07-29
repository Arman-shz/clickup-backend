package ir.arman.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Task.status. Also the value accepted by the `status` query parameter on GET /api/tasks.
 */
public enum TaskStatus {

    TODO("todo"),
    IN_PROGRESS("in_progress"),
    REVIEW("review"),
    DONE("done");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static TaskStatus from(String value) {
        for (TaskStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException(
                "must be one of [todo, in_progress, review, done], was: " + value);
    }

    @Converter(autoApply = true)
    public static class Mapping implements AttributeConverter<TaskStatus, String> {

        @Override
        public String convertToDatabaseColumn(TaskStatus attribute) {
            return attribute == null ? null : attribute.value;
        }

        @Override
        public TaskStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : from(dbData);
        }
    }
}

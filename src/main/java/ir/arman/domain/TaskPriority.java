package ir.arman.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Task.priority. Optional in the spec, so the column is nullable. */
public enum TaskPriority {

    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    private final String value;

    TaskPriority(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static TaskPriority from(String value) {
        for (TaskPriority priority : values()) {
            if (priority.value.equals(value)) {
                return priority;
            }
        }
        throw new IllegalArgumentException("must be one of [low, medium, high], was: " + value);
    }

    @Converter(autoApply = true)
    public static class Mapping implements AttributeConverter<TaskPriority, String> {

        @Override
        public String convertToDatabaseColumn(TaskPriority attribute) {
            return attribute == null ? null : attribute.value;
        }

        @Override
        public TaskPriority convertToEntityAttribute(String dbData) {
            return dbData == null ? null : from(dbData);
        }
    }
}

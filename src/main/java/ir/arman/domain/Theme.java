package ir.arman.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** UserProfile.theme -- light or dark. */
public enum Theme {

    LIGHT("light"),
    DARK("dark");

    private final String value;

    Theme(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static Theme from(String value) {
        for (Theme theme : values()) {
            if (theme.value.equals(value)) {
                return theme;
            }
        }
        throw new IllegalArgumentException("Unknown theme: " + value);
    }

    @Converter(autoApply = true)
    public static class Mapping implements AttributeConverter<Theme, String> {

        @Override
        public String convertToDatabaseColumn(Theme attribute) {
            return attribute == null ? null : attribute.value;
        }

        @Override
        public Theme convertToEntityAttribute(String dbData) {
            return dbData == null ? null : from(dbData);
        }
    }
}

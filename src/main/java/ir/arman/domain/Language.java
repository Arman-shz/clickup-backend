package ir.arman.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** UserProfile.language -- fa or en. */
public enum Language {

    FA("fa"),
    EN("en");

    private final String value;

    Language(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static Language from(String value) {
        for (Language language : values()) {
            if (language.value.equals(value)) {
                return language;
            }
        }
        throw new IllegalArgumentException("must be one of [fa, en], was: " + value);
    }

    @Converter(autoApply = true)
    public static class Mapping implements AttributeConverter<Language, String> {

        @Override
        public String convertToDatabaseColumn(Language attribute) {
            return attribute == null ? null : attribute.value;
        }

        @Override
        public Language convertToEntityAttribute(String dbData) {
            return dbData == null ? null : from(dbData);
        }
    }
}

package ir.arman.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * UserProfile.role -- admin or student. The spec has no other role; `viewer` existed
 * only in the member-invite enum, which was removed along with the endpoint.
 */
public enum Role {

    ADMIN("admin"),
    STUDENT("student");

    /**
     * The same string as {@code ADMIN.value()}, as a compile-time constant.
     *
     * <p>{@code @RolesAllowed} takes literals only, so without this the role would be
     * spelled out again at every use site. It cannot be the constructor argument above --
     * an enum constant may not reference a static field of its own class -- so the two are
     * checked against each other when the class loads instead.
     */
    public static final String ADMIN_VALUE = "admin";

    static {
        if (!ADMIN.value.equals(ADMIN_VALUE)) {
            throw new IllegalStateException("Role.ADMIN_VALUE no longer matches Role.ADMIN");
        }
    }

    private final String value;

    Role(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static Role from(String value) {
        for (Role role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("must be one of [admin, student], was: " + value);
    }

    /** Constant names are upper case, the column is not, so name() cannot be the mapping. */
    @Converter(autoApply = true)
    public static class Mapping implements AttributeConverter<Role, String> {

        @Override
        public String convertToDatabaseColumn(Role attribute) {
            return attribute == null ? null : attribute.value;
        }

        @Override
        public Role convertToEntityAttribute(String dbData) {
            return dbData == null ? null : from(dbData);
        }
    }
}

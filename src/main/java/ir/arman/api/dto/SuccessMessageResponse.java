package ir.arman.api.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
/**
 * The spec's SuccessMessageResponse schema
 * (swagger.yaml, components/schemas/SuccessMessageResponse).
 */
@RegisterForReflection
public record SuccessMessageResponse(boolean success, String message) {

    /** The spec's default success body: "عملیات با موفقیت انجام شد." */
    public static SuccessMessageResponse ok() {
        return new SuccessMessageResponse(true, "عملیات با موفقیت انجام شد.");
    }

    public static SuccessMessageResponse of(String message) {
        return new SuccessMessageResponse(true, message);
    }
}

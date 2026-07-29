package ir.arman.api.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * The spec's CreateReportRequest (swagger.yaml, components/schemas/CreateReportRequest).
 *
 * <p>It carries no {@code userId}, {@code userName} or {@code submittedAt}: all three are
 * stamped from the bearer token and the clock. That is what stops one account filing a
 * report in another's name, so they are not accepted here even as optional properties.
 *
 * <p>{@code hoursWorked} and {@code tasksCompleted} are the boxed types rather than
 * {@code double} and {@code int}, because the spec marks both required and a primitive
 * cannot tell a missing property from a zero -- an omitted week would silently become a
 * week of no work at all.
 *
 * <p>Neither may be negative. The database has no CHECK for that (changelog 001 declares
 * only the column types), so this is the only place it is caught; without it,
 * {@code hoursWorked: -40} is stored and read back. There is no upper bound beyond what
 * the NUMERIC(6,2) column holds: a week has 168 hours, but a report may cover a period
 * the title names and nothing in the spec says otherwise, so refusing 200 would be
 * inventing a rule.
 */
public record CreateReportRequest(

        @NotBlank
        @Size(max = 255)
        String weekTitle,

        @NotNull
        @PositiveOrZero
        @Digits(integer = 4, fraction = 2)
        BigDecimal hoursWorked,

        @NotNull
        @PositiveOrZero
        Integer tasksCompleted,

        String achievements,

        String challenges,

        String nextWeekPlan) {
}

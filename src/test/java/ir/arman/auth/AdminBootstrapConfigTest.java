package ir.arman.auth;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two branches of {@link AdminBootstrap} that never reach a database, tested without
 * one.
 *
 * <p>Both are refusals, and both are cheap to get wrong in the direction that says
 * nothing. The bean has no repository injected here, so if either branch did touch the
 * database these tests would fail with a NullPointerException -- which is the assertion.
 */
class AdminBootstrapConfigTest {

    private static AdminBootstrap configured(String studentId, String password) {
        AdminBootstrap bootstrap = new AdminBootstrap();
        bootstrap.configuredStudentId = Optional.ofNullable(studentId);
        bootstrap.configuredPassword = Optional.ofNullable(password);
        bootstrap.configuredName = "مدیر سیستم";
        return bootstrap;
    }

    @Test
    void doesNothingWhenNoAdministratorWasAskedFor() {
        assertDoesNotThrow(() -> configured(null, null).createFirstAdmin(null));
    }

    @Test
    void treatsAnEmptyVariableAsUnset() {
        // How compose spells "not set": ADMIN_STUDENT_ID= with nothing after it. Reading
        // that as a student id would try to create an account with a blank login.
        assertDoesNotThrow(() -> configured("", "   ").createFirstAdmin(null));
    }

    @Test
    void refusesToStartWithOnlyAStudentId() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> configured("10000000", null).createFirstAdmin(null));

        // Which half is missing has to be in the message: an operator reading a container
        // log that only said "misconfigured" would have two variables to guess between.
        assertTrue(refused.getMessage().contains("Only the student id was set"),
                refused.getMessage());
    }

    @Test
    void refusesToStartWithOnlyAPassword() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> configured(null, "ChangeMe123").createFirstAdmin(null));

        assertTrue(refused.getMessage().contains("Only the password was set"),
                refused.getMessage());
    }
}

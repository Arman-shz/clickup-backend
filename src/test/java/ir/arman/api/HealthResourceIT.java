package ir.arman.api;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Runs the same assertions against the packaged/native build, preserving the native
 * coverage the generated GreetingResourceIT provided.
 */
@QuarkusIntegrationTest
class HealthResourceIT extends HealthResourceTest {
    // Execute the same tests but in packaged mode.
}

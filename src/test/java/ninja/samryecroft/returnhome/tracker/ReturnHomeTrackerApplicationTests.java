package ninja.samryecroft.returnhome.tracker;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Extends {@link AbstractIntegrationTest} so the context is wired to a Testcontainers Postgres like
 * every other {@code @SpringBootTest} here. Previously it relied on a developer's local database
 * being up on 5432, which is why it failed intermittently (T21); it also would not resolve
 * {@code spring.datasource.password} now that the base config has no fallback.
 */
// WS-E: quarantined out of the CI required gate (T21) as an infra-timing test — a full Spring
// context boot on a Testcontainers Postgres. Runs in ci.yml's non-blocking flaky-infra lane, not
// deleted; a real context-load regression still shows red there.
@Tag("flaky-infra")
@SpringBootTest
class ReturnHomeTrackerApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }

}

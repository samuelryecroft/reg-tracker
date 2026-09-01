package ninja.samryecroft.returnhome.tracker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Extends {@link AbstractIntegrationTest} so the context is wired to a Testcontainers Postgres like
 * every other {@code @SpringBootTest} here. Previously it relied on a developer's local database
 * being up on 5432, which is why it failed intermittently (T21); it also would not resolve
 * {@code spring.datasource.password} now that the base config has no fallback.
 */
@SpringBootTest
class ReturnHomeTrackerApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }

}

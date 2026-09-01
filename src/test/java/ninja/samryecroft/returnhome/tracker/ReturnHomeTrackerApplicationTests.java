package ninja.samryecroft.returnhome.tracker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Extends {@link AbstractIntegrationTest} so the context is wired to the Testcontainers Postgres
 * like every other {@code @SpringBootTest} here. It was previously the one test relying on a
 * developer's local database being up on 5432, which is why it failed intermittently (T21 item 1).
 */
@SpringBootTest
class ReturnHomeTrackerApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }

}

package ninja.samryecroft.returnhome.tracker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Extends {@link AbstractIntegrationTest} so the context is wired to a Testcontainers Postgres like
 * every other {@code @SpringBootTest} here. Previously it relied on a developer's local database
 * being up on 5432, which is why it failed intermittently (T21); it also would not resolve
 * {@code spring.datasource.password} now that the base config has no fallback.
 */
// T212: quarantined out of the required gate as infra-timing (T21) until 2026-09-08. The javadoc
// above is the argument for removing it: the intermittency was "relied on a developer's local
// database being up on 5432", and this class now extends AbstractIntegrationTest and gets a
// Testcontainers Postgres like everything else. IT RECORDED ITS OWN CURE IN THE PAST TENSE AND WENT
// ON CARRYING THE TAG FOR THE DISEASE. Nothing here can fail for the reason it was quarantined for.
@SpringBootTest
class ReturnHomeTrackerApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }

}

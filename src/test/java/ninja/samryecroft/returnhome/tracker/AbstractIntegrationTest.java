package ninja.samryecroft.returnhome.tracker;

import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import ninja.samryecroft.returnhome.tracker.config.AdminUserSeeder;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.FieldKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres container for the whole test run, with the database reset before every test.
 *
 * <p>It used to be a JUnit-managed {@code @Container}, which starts and stops the container around
 * each test class. Spring caches an application context across test classes, so a class that reused
 * a context built for an earlier class got a Hikari pool pointing at a container that had already
 * been stopped - "Connection refused", surfacing as a 30s pool timeout. Whether it happened
 * depended on class ordering, which is what made it look like flakiness rather than a bug (T21).
 *
 * <p>Sharing one container fixes that, but it also means the classes now share a database, so
 * {@link #resetDatabase()} puts each test back to the state a freshly-migrated database is in:
 * every transactional table emptied, and the bootstrap admin re-seeded exactly as it would be on a
 * real first boot. Without that, rows from one class leak into another's queries.
 *
 * <p>The container is deliberately never stopped: Testcontainers' Ryuk sidecar removes it when the
 * JVM exits.
 */
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    /**
     * Tables that must survive a reset: Flyway's own bookkeeping, plus the reference data seeded by
     * V4/V5/V9 that the tests read rather than write. Emptying those would leave the schema in a
     * state no real deployment is ever in.
     *
     * <p>Everything else is discovered from the database rather than listed, so a table added by a
     * future migration is reset automatically instead of silently leaking rows between classes.
     */
    private static final Set<String> PRESERVED_TABLES =
            Set.of("flyway_schema_history", "organisations", "theme_settings");

    private static volatile String truncateStatement;

    @Autowired
    private DataSource dataSource;

    /** Absent in slice tests such as {@code @DataJpaTest}, which do not create application beans. */
    @Autowired(required = false)
    private AdminUserSeeder adminUserSeeder;

    /**
     * The field-key cache outlives the truncate below, because the Spring context is reused across
     * test classes while the database is emptied between them. Left alone, a later test would
     * encrypt under a cached key whose wrapped copy in {@code org_field_key} had just been deleted -
     * columns nothing could ever unwrap again. Nothing truncates that table in production, so this
     * is a test-harness concern rather than a live one, but the cache and the row have to be
     * discarded together wherever one of them goes.
     */
    @Autowired(required = false)
    private FieldKeyService fieldKeyService;

    /**
     * Runs before any subclass {@code @BeforeEach} - JUnit invokes superclass lifecycle methods
     * first - so a test's own seed data is written into an empty database.
     */
    @BeforeEach
    void resetDatabase() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // One statement so the cascade and the identity restart apply atomically; TRUNCATE takes an
        // ACCESS EXCLUSIVE lock per table and doing them separately invites deadlocks.
        jdbc.execute(truncateStatement(jdbc));
        if (fieldKeyService != null) {
            fieldKeyService.clearCache();
        }
        if (adminUserSeeder != null) {
            // Re-create the bootstrap admin the truncate just removed, so a context that seeded one
            // at startup still has it. Skips itself when no seed password is configured, which is
            // exactly what happens on a real boot.
            adminUserSeeder.run(null);
        }
    }

    private static String truncateStatement(JdbcTemplate jdbc) {
        String statement = truncateStatement;
        if (statement == null) {
            List<String> tables = jdbc.queryForList(
                    "SELECT tablename FROM pg_tables WHERE schemaname = current_schema()", String.class)
                    .stream()
                    .filter(table -> !PRESERVED_TABLES.contains(table))
                    .sorted()
                    .toList();
            statement = "TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE";
            truncateStatement = statement;
        }
        return statement;
    }
}

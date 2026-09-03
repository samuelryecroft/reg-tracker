package ninja.samryecroft.returnhome.tracker;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
     * V4/V5/V9. Emptying those would leave the schema in a state no real deployment is ever in.
     *
     * <p>Everything else is discovered from the database rather than listed, so a table added by a
     * future migration is reset automatically instead of silently leaking rows between classes.
     *
     * <p>Preserving a table is not the same as it being read-only, which is what
     * {@link #dropNonReferenceOrganisations} exists to handle.
     */
    private static final Set<String> PRESERVED_TABLES =
            Set.of("flyway_schema_history", "organisations", "theme_settings");

    private static volatile String truncateStatement;
    private static volatile List<Long> referenceOrganisationIds;

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
        dropNonReferenceOrganisations(jdbc);
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

    /**
     * Puts {@code organisations} back to just the rows the migrations seeded.
     *
     * <p>{@code organisations} is preserved from the truncate because V5's two rows are reference
     * data, and nine test classes read them as such - they take
     * {@code findByTypeOrderByName(type).get(0)} to mean "the one V5 seeded". But three classes
     * (dashboard, audit-feed, case-file-export) also <em>create</em> organisations, and a preserved
     * table never gives those back. Once a leftover sorts ahead of the seeded row by name, that
     * {@code get(0)} silently starts returning somebody else's organisation.
     *
     * <p>That is worse than an ordinary leak, because the supplier and the care provider are looked
     * up separately. Take "Feed Supplier" as the first supplier while "Default Care Provider" is
     * still the first care provider, and the pair is no longer linked: the coordinator now belongs
     * to a supplier that does not serve the request's home, so allocating one correctly fails
     * {@code OrganisationAccessService} with a 403. Whether it happens depends on class ordering,
     * which is filesystem order and therefore differs between a macOS laptop and a Linux CI runner
     * - the suite passed locally and failed on CI for exactly this reason (T120).
     *
     * <p>The reference ids are read on the first reset rather than hard-coded, so a migration that
     * seeds a third organisation needs no change here. That first reset is safe to snapshot from:
     * it runs before any test body, and this superclass hook runs before the subclass's own
     * {@code @BeforeEach}, so nothing has written yet.
     */
    private static void dropNonReferenceOrganisations(JdbcTemplate jdbc) {
        List<Long> reference = referenceOrganisationIds;
        if (reference == null) {
            referenceOrganisationIds = jdbc.queryForList("SELECT id FROM organisations", Long.class);
            return;
        }
        String ids = reference.isEmpty() ? "NULL"
                : reference.stream().map(String::valueOf).collect(Collectors.joining(", "));
        // theme_settings is preserved too and carries an FK to organisations, so its rows for a
        // test-created organisation have to go first.
        jdbc.update("DELETE FROM theme_settings WHERE organisation_id IS NOT NULL"
                + " AND organisation_id NOT IN (" + ids + ")");
        // Parent and child organisations go in one statement so the self-referencing
        // supplier_organisation_id FK is only checked once both have gone.
        jdbc.update("DELETE FROM organisations WHERE id NOT IN (" + ids + ")");
        // RESTART IDENTITY covered the truncated tables; this is the preserved table's equivalent,
        // so a test-created organisation gets the same id every run rather than climbing.
        jdbc.execute("SELECT setval('organisations_id_seq',"
                + " COALESCE((SELECT MAX(id) FROM organisations), 1), true)");
    }
}

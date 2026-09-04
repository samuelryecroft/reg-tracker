package ninja.samryecroft.returnhome.tracker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import ninja.samryecroft.returnhome.tracker.config.AdminUserSeeder;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.FieldKeyService;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
 *
 * <p>{@link #DOCUMENT_STORE} is the same arrangement for the document store, for the same reason
 * and arrived at the same way. Six test classes each declared their own {@code @TempDir} plus a
 * {@code @DynamicPropertySource} pointing {@code app.documents.local.directory} at it. Registering
 * an identical value is not enough to share a context: Spring keys its test-context cache on
 * {@code DynamicPropertiesContextCustomizer}, whose {@code equals} compares the <em>set of
 * registrar methods</em> and never looks at what they register - so six identical method bodies on
 * six classes were six cache keys, six contexts and six Hikari pools against one Postgres. Ten
 * pools of ten exhausts the container's hundred connections, and the eleventh context died on
 * "FATAL: sorry, too many clients already" - always in whichever class happened to be eleventh, so
 * it read as flakiness rather than as a bug (T148, the same shape as the T21 story above).
 */
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    /**
     * One document store for the whole run, emptied before every test by {@link #resetDatabase()}.
     *
     * <p>Deliberately not a {@code @TempDir}. That annotation is resolved per test class, and this
     * field is static on a superclass every integration test shares, so each class would overwrite
     * it with a fresh directory and delete it again afterwards - while the cached Spring context
     * kept the {@code LocalFileStorageProvider} built from whichever value was registered first.
     * Every later class would then be writing through a bean that points at a deleted path. A
     * plain directory created once, like the container above, has no such per-class lifecycle.
     */
    protected static final Path DOCUMENT_STORE = createDocumentStore();

    /**
     * The single registrar for {@code app.documents.local.directory}. It has to live here rather
     * than on each test class precisely because the method identity <em>is</em> the cache key - see
     * the class javadoc.
     */
    @DynamicPropertySource
    static void documentStoreDirectory(DynamicPropertyRegistry registry) {
        registry.add("app.documents.local.directory", DOCUMENT_STORE::toString);
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

    /** Absent in slice tests, same as {@link #adminUserSeeder}. */
    @Autowired(required = false)
    private OrganisationRepository organisationRepository;

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
        clearDocumentStore();
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

    private static Path createDocumentStore() {
        try {
            Path directory = Files.createTempDirectory("rht-test-documents");
            // The container above is left to Ryuk; this is the equivalent, since a directory the
            // tests keep writing into cannot be deleted until the JVM is done with it.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(directory)));
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the shared test document store", e);
        }
    }

    /**
     * Empties the store without removing it, so the path the cached context resolved at startup
     * stays valid. The database equivalent is the truncate above: a test starts from an empty
     * store rather than seeing documents an earlier one approved.
     */
    private static void clearDocumentStore() {
        try (var entries = Files.list(DOCUMENT_STORE)) {
            entries.forEach(AbstractIntegrationTest::deleteRecursively);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not clear the shared test document store", e);
        }
    }

    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + root, e);
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
     * data, and nine test classes read them as such. They used to do it with
     * {@code findByTypeOrderByName(type).get(0)}, meaning "the one V5 seeded" - they now call
     * {@link #seededOrganisations()}, which does not depend on sort order (T123). But three classes
     * (dashboard, audit-feed, case-file-export) also <em>create</em> organisations, and a preserved
     * table never gives those back, so the strays still have to go: they would otherwise accumulate
     * across the whole run and leak into any query that is not filtered by organisation. When the
     * idiom was positional, a leftover sorting ahead of the seeded row by name silently changed the
     * answer.
     *
     * <p>That was worse than an ordinary leak, because the supplier and the care provider were
     * looked up separately. Take "Feed Supplier" as the first supplier while "Default Care Provider" is
     * still the first care provider, and the pair is no longer linked: the coordinator now belongs
     * to a supplier that does not serve the request's home, so allocating one correctly fails
     * {@code OrganisationAccessService} with a 403. Whether it happens depends on class ordering,
     * which is filesystem order and therefore differs between a macOS laptop and a Linux CI runner
     * - the suite passed locally and failed on CI for exactly this reason (T120). {@link
     * #seededOrganisations()} closes that off at the reading end as well.
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

    /**
     * The pair of organisations the migrations seed: a supplier, and the care provider it serves.
     *
     * @param supplier     V5's SUPPLIER row
     * @param careProvider V5's CARE_PROVIDER row, whose {@code supplier_organisation_id} is the supplier
     */
    protected record SeededOrganisations(Organisation supplier, Organisation careProvider) {
    }

    /**
     * Resolves the seeded organisations by identity rather than by sort-order position.
     *
     * <p>Tests used to say {@code findByTypeOrderByName(type).get(0)} and mean "the one V5 seeded".
     * That is only true while no test-created organisation sorts ahead of it by name, and the
     * moment one does the call silently returns somebody else's organisation instead of failing.
     * Worse, supplier and care provider were looked up in two separate calls, so the pair could come
     * apart - a coordinator belonging to a supplier that does not serve the request's home, which
     * {@code OrganisationAccessService} then correctly rejects with a 403. Whether it happened
     * depended on class ordering, so the suite passed on a laptop and failed on CI (T120).
     *
     * <p>{@link #dropNonReferenceOrganisations} keeps that idiom honest by deleting the strays, and
     * this resolves the rows without relying on it: the reference ids it already snapshots from the
     * freshly-migrated database ARE the stable key, so there is no name or id literal here to drift
     * from the migration. Type then separates the two, and the link between them is asserted rather
     * than assumed - the pair is returned together because it is only meaningful together.
     *
     * <p>A migration that seeds a third organisation fails this loudly and on purpose, rather than
     * quietly picking one of them.
     */
    protected SeededOrganisations seededOrganisations() {
        if (organisationRepository == null) {
            throw new IllegalStateException(
                    "seededOrganisations() needs an OrganisationRepository, which a slice test context "
                            + "does not create - use @SpringBootTest");
        }
        List<Long> reference = referenceOrganisationIds;
        if (reference == null) {
            throw new IllegalStateException(
                    "The seeded organisation ids are snapshotted by the first resetDatabase(), which "
                            + "runs before any test body - so this should be unreachable");
        }
        // findAllWithSupplier fetches supplierOrganisation through an @EntityGraph; the association is
        // LAZY, and these helpers are called from @BeforeEach outside any transaction.
        List<Organisation> seeded = organisationRepository.findAllWithSupplier().stream()
                .filter(organisation -> reference.contains(organisation.getId()))
                .toList();
        Organisation supplier = exactlyOne(seeded, OrgType.SUPPLIER);
        Organisation careProvider = exactlyOne(seeded, OrgType.CARE_PROVIDER);
        Organisation servedBy = careProvider.getSupplierOrganisation();
        if (servedBy == null || !servedBy.getId().equals(supplier.getId())) {
            throw new IllegalStateException("Seeded care provider '" + careProvider.getName()
                    + "' is not served by seeded supplier '" + supplier.getName()
                    + "' - the pair a test needs is not a pair");
        }
        return new SeededOrganisations(supplier, careProvider);
    }

    /** The supplier V5 seeds. See {@link #seededOrganisations()}. */
    protected Organisation seededSupplier() {
        return seededOrganisations().supplier();
    }

    /** The care provider V5 seeds, served by {@link #seededSupplier()}. */
    protected Organisation seededCareProvider() {
        return seededOrganisations().careProvider();
    }

    private static Organisation exactlyOne(List<Organisation> seeded, OrgType type) {
        List<Organisation> matching = seeded.stream()
                .filter(organisation -> organisation.getType() == type)
                .toList();
        if (matching.size() != 1) {
            throw new IllegalStateException("Expected exactly one seeded " + type + " organisation but found "
                    + matching.size() + " " + matching.stream().map(Organisation::getName).toList()
                    + " - a migration has changed the reference data, so seededOrganisations() needs updating");
        }
        return matching.get(0);
    }
}

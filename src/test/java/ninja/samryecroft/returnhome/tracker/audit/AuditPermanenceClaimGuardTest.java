package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * T119 6c: the permanence sentence on the audit event page and the database trigger that makes it
 * true must not drift apart.
 *
 * <p>The page states, to an audience that includes an IRO, a local authority and a court:
 * <em>"Audit entries cannot be edited or deleted — the database itself rejects the attempt, not only
 * the application."</em> That is a claim about {@code audit_events_no_update_or_delete}, a
 * {@code BEFORE UPDATE OR DELETE} trigger created in {@code V11__add_audit_events.sql} which
 * {@code RAISE EXCEPTION}s.
 *
 * <p><b>The claim and its enforcement are in different files, and the pair fails OPEN.</b> Drop the
 * trigger in a later migration and nothing breaks: the page goes on making the promise, no test
 * turns red, and the assurance quietly becomes false while still being printed. That is the same
 * shape as the break-glass alert marker duplicated between Java and Terraform, and it gets the same
 * treatment - both sides asserted here so neither can move alone.
 *
 * <p><b>Deliberately checked against the migration and not against a live database.</b> A test that
 * tried an UPDATE and expected an exception would be the stronger check, and it needs a container -
 * so on a machine without Docker it could only run in CI. This one runs anywhere, and it is honest
 * about what it proves: that the trigger is still declared, not that PostgreSQL still honours it.
 *
 * <p>What it does <em>not</em> cover, on purpose: a table owner can {@code DISABLE TRIGGER}, and
 * {@code TRUNCATE} does not fire row-level triggers at all. That is a threat-model entry - the
 * runtime role must not own the table - and not something a page an IRO reads should be caveated
 * with. Recorded here so the boundary is written down next to the claim.
 */
class AuditPermanenceClaimGuardTest {

    private static final Path PAGE = Path.of("src/main/resources/templates/audit/event.html");
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V11__add_audit_events.sql");

    @Test
    void thePageOnlyClaimsDatabaseLevelPermanenceWhileTheTriggerExists() throws IOException {
        String page = withoutComments(Files.readString(PAGE, StandardCharsets.UTF_8));
        String migration = withoutSqlComments(Files.readString(MIGRATION, StandardCharsets.UTF_8));

        boolean pageClaimsIt = page.contains("the database itself\n            rejects the attempt")
                || page.replaceAll("\\s+", " ").contains("the database itself rejects the attempt");

        assertThat(pageClaimsIt)
                .as("the guard must find the sentence it is guarding - if this wording changed, "
                        + "this test is checking nothing and should be updated deliberately rather "
                        + "than left passing")
                .isTrue();

        assertThat(migration)
                .as("audit/event.html tells a court that the DATABASE refuses to change an audit "
                        + "entry. That is a claim about audit_events_no_update_or_delete. If the "
                        + "trigger goes and the sentence stays, the page keeps making a promise "
                        + "nothing enforces - and nothing else would turn red, because the two live "
                        + "in different files and the pair fails OPEN")
                .contains("CREATE TRIGGER audit_events_no_update_or_delete")
                .contains("BEFORE UPDATE OR DELETE ON audit_events");

        assertThat(migration)
                .as("the trigger must RAISE, not swallow: a DO INSTEAD NOTHING rule would make a "
                        + "tamper succeed silently, which is worse than failing loudly and is the "
                        + "distinction V11's own comment draws")
                .contains("RAISE EXCEPTION");
    }

    /**
     * SQL comments stripped before the trigger is looked for, and this was found by arming the
     * test rather than by foresight.
     *
     * <p>The first version searched the raw file. Commenting the statement out - {@code --
     * CREATE TRIGGER audit_events_no_update_or_delete_REMOVED}, which is what a person disabling
     * it would plausibly write - left the substring present, so <b>the guard passed on a schema
     * with no trigger in it</b>, which is precisely the state it exists to catch.
     *
     * <p>It is the third instance of one shape in this cluster: a scanner reading a file's own
     * commentary as if it were the file. The 4d rationale comment containing a literal
     * {@code <table} broke that screen's no-table assertion; the tag-balance guard read a
     * {@code <style>} inside a comment; and this read commented-out SQL. <b>Every source scanner
     * has to strip the comment syntax of the language it is scanning, and the failure is silent in
     * both directions.</b>
     */
    private static String withoutSqlComments(String sql) {
        return sql.replaceAll("(?m)--.*$", "").replaceAll("(?s)/\\*.*?\\*/", "");
    }

    /** A comment quoting the claim is not the page making it. */
    private static String withoutComments(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }
}

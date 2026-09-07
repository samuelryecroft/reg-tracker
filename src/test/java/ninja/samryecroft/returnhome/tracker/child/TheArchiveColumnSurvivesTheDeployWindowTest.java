package ninja.samryecroft.returnhome.tracker.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V21's deploy-window argument, MEASURED rather than asserted in a comment (T321).
 *
 * <p>The DB-plane job runs Flyway to completion <b>before</b> the new jar goes live, so for a few
 * minutes the OLD jar - which knows nothing about this column - is writing children into the NEW
 * schema. If that insert fails, adding a young person 500s mid-onboarding.
 *
 * <p><b>This is a test rather than a paragraph because the argument CHANGED and the paragraph did
 * not have to.</b> When the column was {@code archived BOOLEAN NOT NULL DEFAULT FALSE}, the omitted
 * column was filled in by the default. The column is now a nullable timestamp with <b>no default at
 * all</b>, so if that were still the reason, this would be a constraint violation. It holds for a
 * different reason - there is no {@code NOT NULL} either, and {@code NULL} is not a placeholder
 * here, it <i>is</i> "not archived". A migration rewrite that reintroduced {@code NOT NULL},
 * reasonably enough on the grounds that a state column should not be null, would be correct-looking
 * and would take the onboarding form down during a deploy. Nothing else in the suite would notice,
 * because every other test writes through the CURRENT jar, which always mentions the column.
 *
 * <p>So the insert here deliberately does NOT go through the repository. Going through Hibernate
 * would be testing today's jar against today's schema, which is the one combination that is never
 * in doubt.
 */
@SpringBootTest
class TheArchiveColumnSurvivesTheDeployWindowTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private HomeRepository homeRepository;
    private Long homeId;

    @BeforeEach
    void seedAHome() {
        Home home = new Home();
        home.setName("T321 Deploy Window House-" + System.nanoTime());
        home.setOrganisation(seededCareProvider());
        homeId = homeRepository.save(home).getId();
    }

    /** The old jar's insert: every column it knows about, and no mention of the new one. */
    @Test
    void aChildInsertedWithoutMentioningTheColumnIsAccepted() {
        assertThatCode(() -> jdbc.update(
                "insert into children (first_name_enc, last_name_enc, date_of_birth_enc, home_id, created_at) "
                        + "values (?, ?, ?, ?, now())",
                "ciphertext-first", "ciphertext-last", "ciphertext-dob", homeId))
                .as("the old jar omits archived_at; if this throws, adding a young person 500s "
                        + "for the length of the deploy window")
                .doesNotThrowAnyException();
    }

    /**
     * AND THE ROW IT LEAVES BEHIND IS ON THE ROLL, which is the half a "does it insert" test alone
     * would miss.
     *
     * <p><b>MEASURED, and it is why this assertion exists as its own test.</b> Arming the migration
     * with {@code NOT NULL DEFAULT now()} - the reasonable-looking rewrite, on the grounds that a
     * state column should not be null - leaves the test above <b>GREEN</b>. The insert succeeds. It
     * is this one that goes red, because every child added during the deploy window is written with
     * an archive date and is therefore <b>archived the moment they are created</b>: gone from every
     * list, for exactly the children added while a release is going out, with no error anywhere.
     *
     * <p>So "does the insert still work" was the wrong question, or at least only half of it. The
     * absence of a value has to <b>mean</b> not-archived, not merely be permitted.
     */
    @Test
    void andThatChildIsOnTheActiveListRatherThanQuietlyArchived() {
        jdbc.update("insert into children (first_name_enc, last_name_enc, date_of_birth_enc, home_id, created_at) "
                        + "values (?, ?, ?, ?, now())",
                "ciphertext-first", "ciphertext-last", "ciphertext-dob", homeId);

        assertThat(jdbc.queryForObject(
                "select count(*) from children where home_id = ? and archived_at is null", Integer.class, homeId))
                .as("the predicate every list query in ChildRepository uses, asked of the row the "
                        + "old jar would have written")
                .isEqualTo(1);

        // ASKED IN SQL RATHER THAN THROUGH THE REPOSITORY, and the reason is worth recording because
        // it looks like a shortcut and is not. Reading this row through ChildRepository throws:
        // FieldCryptoException, "Value in Child.firstName is not in the expected encrypted form -
        // refusing to return it rather than treating it as plaintext". That is the encryption guard
        // working exactly as designed on a row whose ciphertext columns I wrote by hand, and no
        // amount of care with this fixture gets past it without real key material. The predicate is
        // the subject here, so the predicate is what is asked, in the language the schema answers in.
        assertThat(jdbc.queryForObject(
                "select count(*) from children where home_id = ? and archived_at is not null", Integer.class, homeId))
                .as("and nothing put it on the archived side of the same predicate")
                .isZero();
    }

    /**
     * The two schema properties the argument above rests on, named rather than inferred from the
     * insert succeeding.
     *
     * <p>An insert can succeed for the wrong reason - a default would also make it pass - so this
     * asserts that there is no default AND that null is permitted. Together they are the whole of
     * why a nullable timestamp needs no default where the boolean did.
     */
    @Test
    void theColumnIsNullableAndHasNoDefault() {
        var column = jdbc.queryForMap(
                "select is_nullable, column_default from information_schema.columns "
                        + "where table_name = 'children' and column_name = 'archived_at'");

        assertThat(column.get("is_nullable")).isEqualTo("YES");
        assertThat(column.get("column_default"))
                .as("a default would mean the deploy-window argument is the boolean's, not this one's")
                .isNull();
    }
}

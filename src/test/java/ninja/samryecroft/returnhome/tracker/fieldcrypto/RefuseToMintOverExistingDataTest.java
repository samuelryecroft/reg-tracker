package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.document.KeyProvider;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T106: a field key is never minted for an organisation that already owns encrypted rows.
 *
 * <p><b>The case is a point-in-time restore.</b> {@code org_field_key} has a trigger refusing
 * DELETE and T103's runbook is the primary control, but <b>neither can see a restore</b> - a
 * rollback issues no DELETE. Afterwards the key row is gone and the ciphertext is not.
 *
 * <p>A read then fails loudly and is not the dangerous case. <b>The dangerous case is a WRITE:</b>
 * without this guard the store finds no row, mints a fresh key, and the organisation ends up with
 * two generations of ciphertext of which the older is permanently unreadable - while every new row
 * reads back perfectly, so the estate looks healthy and nothing reports it.
 *
 * <p>The first test deletes the key row <b>with the trigger disabled</b>, which is the only way to
 * reach from this side the state a restore reaches from the other. Disabling it is the point: the
 * trigger is a real control and this defect is defined by going around it.
 */
@SpringBootTest
class RefuseToMintOverExistingDataTest extends AbstractIntegrationTest {

    @Autowired private OrgFieldKeyStore keyStore;
    @Autowired private OrgFieldKeyRepository keyRepository;
    @Autowired private KeyProvider keyProvider;
    @Autowired private OrganisationRepository organisationRepository;
    @Autowired private HomeRepository homeRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Organisation organisation;
    private String suffix;

    @BeforeEach
    void seed() {
        suffix = "-" + System.nanoTime();
        Organisation provider = new Organisation();
        provider.setName("Restore Provider" + suffix);
        provider.setType(OrgType.CARE_PROVIDER);
        provider.setSupplierOrganisation(seededSupplier());
        organisation = organisationRepository.save(provider);
    }

    @Test
    void aKeyIsRefusedForAnOrganisationThatAlreadyHoldsEncryptedRows() {
        saveAnEncryptedChild();
        assertThat(keyRepository.findByOrganisationId(organisation.getId()))
                .as("writing an encrypted row is what mints the key in the first place")
                .isPresent();

        simulateRestoreThatRolledBackTheKeyTable();

        assertThatThrownBy(() -> keyStore.loadOrCreate(organisation.getId()))
                .isInstanceOf(FieldCryptoException.class)
                .as("the message is read by whoever is holding a restored database at an unsociable "
                        + "hour: it names the organisation, says what it concluded, and says what to "
                        + "do instead of minting")
                .hasMessageContaining(String.valueOf(organisation.getId()))
                .hasMessageContaining("already holds encrypted data")
                .hasMessageContaining("permanently unreadable")
                .hasMessageContaining("Restore the org_field_key table from backup");

        assertThat(keyRepository.findByOrganisationId(organisation.getId()))
                .as("and nothing was minted - a refusal that still wrote the row would be worse "
                        + "than no guard, because the message would say it had not")
                .isEmpty();
    }

    /**
     * <b>Not knowing refuses too, and says something different.</b> A query that cannot answer is
     * exactly when we know least, and it is the moment after a restore when the database is least
     * trustworthy - so treating "I could not check" as "there is nothing there" would reintroduce
     * the irreversible case precisely where it is most likely.
     *
     * <p>The two messages are asserted to be <em>distinguishable</em>, because an operator who
     * cannot tell "it already holds data" from "the check failed" will retry the one that is broken.
     */
    @Test
    void aCheckThatCannotAnswerAlsoRefusesAndSaysSoInDifferentWords() {
        EncryptedDataProbe cannotAnswer = new EncryptedDataProbe(null) {
            @Override
            public boolean organisationHoldsEncryptedRows(long organisationId) {
                throw new IllegalStateException("the probe could not run");
            }
        };

        assertThatThrownBy(() ->
                new OrgFieldKeyStore(keyRepository, keyProvider, cannotAnswer)
                        .loadOrCreate(organisation.getId()))
                .isInstanceOf(FieldCryptoException.class)
                .hasMessageContaining("THE CHECK COULD NOT RUN")
                .as("it must not be READ as a finding - the check failed, which is not a report "
                        + "about the data")
                .hasMessageContaining("This is not a finding about the data")
                .as("and it must not be MISTAKABLE for the other refusal, including by substring: "
                        + "the first wording said 'could not determine whether it already holds "
                        + "encrypted data', which contains the other message's key phrase verbatim, "
                        + "so anyone grepping logs for it would have matched both")
                .hasMessageNotContaining("already holds encrypted data");

        assertThat(keyRepository.findByOrganisationId(organisation.getId())).isEmpty();
    }

    @Test
    void anOrganisationWithNoEncryptedRowsStillGetsItsFirstKey() {
        assertThatCode(() -> keyStore.loadOrCreate(organisation.getId()))
                .as("this is a constraint, not a wall: the ordinary first write must still work, "
                        + "and a guard that blocked it would be the outage its own reasoning "
                        + "accepts only as the SURVIVABLE mistake, not as the expected one")
                .doesNotThrowAnyException();

        assertThat(keyRepository.findByOrganisationId(organisation.getId())).isPresent();
    }

    private void saveAnEncryptedChild() {
        Home home = new Home();
        home.setName("Restore House" + suffix);
        home.setOrganisation(organisation);
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Restored");
        child.setLastName("Child");
        child.setDateOfBirth(LocalDate.of(2011, 6, 2));
        child.setHome(home);
        childRepository.saveAndFlush(child);
    }

    /**
     * What a point-in-time restore does to this table, reached the only way it can be reached from
     * inside the application: by stepping around the DELETE trigger. The trigger is a real control
     * and remains one - this defect is defined by an event that never issues a DELETE at all.
     */
    private void simulateRestoreThatRolledBackTheKeyTable() {
        jdbcTemplate.execute("ALTER TABLE org_field_key DISABLE TRIGGER org_field_key_no_delete");
        try {
            jdbcTemplate.update("DELETE FROM org_field_key WHERE organisation_id = ?",
                    organisation.getId());
        } finally {
            jdbcTemplate.execute("ALTER TABLE org_field_key ENABLE TRIGGER org_field_key_no_delete");
        }
        assertThat(keyRepository.findByOrganisationId(organisation.getId()))
                .as("the simulation must actually have removed the row, or every assertion after "
                        + "it passes for the wrong reason")
                .isEmpty();
    }
}

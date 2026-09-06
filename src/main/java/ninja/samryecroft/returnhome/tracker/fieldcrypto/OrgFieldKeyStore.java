package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.document.DocumentSecurityException;
import ninja.samryecroft.returnhome.tracker.document.KeyHandle;
import ninja.samryecroft.returnhome.tracker.document.KeyProvider;
import ninja.samryecroft.returnhome.tracker.document.WrappedKey;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and creates the wrapped per-organisation field keys.
 *
 * <p>A separate bean from {@link FieldKeyService} for one reason that is easy to get wrong: the
 * create path must run in its own transaction, and Spring's {@code @Transactional} is applied by a
 * proxy, so a call from one method of a class to another method of the <em>same</em> class does not
 * go through it. Keeping this here is what makes {@code REQUIRES_NEW} real rather than decorative.
 */
@Service
public class OrgFieldKeyStore {

    private static final int DATA_KEY_LENGTH_BYTES = 32;

    private final OrgFieldKeyRepository repository;
    private final KeyProvider keyProvider;
    private final EncryptedDataProbe encryptedDataProbe;
    private final SecureRandom secureRandom = new SecureRandom();

    public OrgFieldKeyStore(OrgFieldKeyRepository repository, KeyProvider keyProvider,
            EncryptedDataProbe encryptedDataProbe) {
        this.repository = repository;
        this.keyProvider = keyProvider;
        this.encryptedDataProbe = encryptedDataProbe;
    }

    /**
     * The organisation's wrapped key, creating it on first use.
     *
     * <p>Committed in its own transaction so that an organisation's first key survives even if the
     * business transaction that happened to trigger it rolls back. The alternative - losing the row
     * while a column encrypted under it is committed elsewhere - is unrecoverable; an unused key
     * row costs nothing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrgFieldKey loadOrCreate(long organisationId) {
        Optional<OrgFieldKey> existing = repository.findByOrganisationId(organisationId);
        if (existing.isPresent()) {
            return existing.get();
        }
        refuseToMintOverExistingData(organisationId);
        try {
            return repository.saveAndFlush(create(organisationId));
        } catch (DataIntegrityViolationException raced) {
            // Two requests created this organisation's first key at once. The unique constraint on
            // organisation_id is what makes that safe: without it one of them would quietly get a
            // second key, and columns written under the loser would never decrypt again.
            return repository.findByOrganisationId(organisationId)
                    .orElseThrow(() -> new FieldCryptoException("No field key for organisation "
                            + organisationId + " after a concurrent create", raced));
        }
    }

    /**
     * Refuses to create a first key for an organisation that already owns encrypted rows.
     *
     * <p><b>The case this exists for is a point-in-time restore.</b> {@code org_field_key} has a
     * trigger refusing DELETE, and T103's runbook is the primary control - but neither can see a
     * restore, because a rollback issues no DELETE. After one, the key row is gone and the ciphertext
     * is not.
     *
     * <p><b>A read then fails loudly and is not the dangerous case.</b> The dangerous one is a WRITE:
     * without this, {@code loadOrCreate} finds no row, mints a fresh key, and the organisation ends
     * up with two generations of ciphertext of which the older is <b>permanently unreadable</b> -
     * while every new row reads back perfectly, so the estate looks healthy. Nothing reports it.
     *
     * <p><b>This is not a new invariant. It is enforcement of one the schema already claims and
     * cannot defend</b> (Kevin): {@code V13} makes {@code organisation_id} UNIQUE and its own comment
     * calls that constraint load-bearing, so the system already asserts one key per organisation
     * forever. The restore breaks it in the single way a unique constraint structurally cannot see -
     * after the rollback the old row does not exist, so the insert is perfectly legal.
     *
     * <p><b>Why fail closed, given that a wrong guard stops an organisation working.</b> Because the
     * two mistakes are not equally bad: a wrong refusal costs an OUTAGE, which announces itself,
     * destroys nothing, and leaves every byte on disk; a wrong mint costs HISTORY that cannot be got
     * back, silently, on a child's safeguarding record. <b>Where two designs are both defensible,
     * prefer the one whose mistakes are survivable.</b>
     *
     * <p><b>Not knowing refuses too, and says something different.</b> A query that cannot answer is
     * precisely when we know least - and it is the moment after a restore, when the database is
     * least trustworthy. But "this organisation already holds encrypted data" and "I could not
     * determine whether it does" are different facts, and <b>an operator at three in the morning who
     * cannot tell them apart will retry the one that is broken.</b>
     *
     * <p>Rotation is unaffected and the objection is dead: rotation re-wraps the existing data key
     * under a new KEK version and updates the row. It does not insert a second one, and the unique
     * constraint would forbid it anyway. <b>Rotation does not mint, so this cannot block it.</b>
     */
    private void refuseToMintOverExistingData(long organisationId) {
        boolean holdsEncryptedRows;
        try {
            holdsEncryptedRows = encryptedDataProbe.organisationHoldsEncryptedRows(organisationId);
        } catch (RuntimeException cannotTell) {
            // Worded so it cannot be mistaken for the refusal below, INCLUDING BY SUBSTRING. The
            // first version read "could not determine whether it already holds encrypted data",
            // which contains the other message's key phrase verbatim - so anyone grepping logs for
            // that phrase would have matched both, and the two are exactly what must be told apart.
            // Caught by the test asserting they are distinguishable rather than by reading them.
            throw new FieldCryptoException("Refusing to create a field key for organisation "
                    + organisationId + ": THE CHECK COULD NOT RUN, so whether this organisation has"
                    + " data encrypted under a previous key is unknown. This is not a finding about"
                    + " the data. Do not retry until the database is known good; if this follows a"
                    + " restore, restore the org_field_key table from backup rather than letting a"
                    + " new key be minted.", cannotTell);
        }
        if (holdsEncryptedRows) {
            throw new FieldCryptoException("Refusing to create a field key for organisation "
                    + organisationId + ": it already holds encrypted data, so a key for it existed"
                    + " and is now missing. Minting a new one would leave that data permanently"
                    + " unreadable. Restore the org_field_key table from backup.");
        }
    }

    private OrgFieldKey create(long organisationId) {
        byte[] dataKey = new byte[DATA_KEY_LENGTH_BYTES];
        secureRandom.nextBytes(dataKey);
        try {
            KeyHandle handle = keyProvider.currentKeyFor(organisationId);
            WrappedKey wrapped = keyProvider.wrap(handle, dataKey);
            return new OrgFieldKey(organisationId, wrapped.keyName(), wrapped.keyVersion(),
                    wrapped.wrapAlgorithm(), wrapped.material());
        } catch (DocumentSecurityException e) {
            throw new FieldCryptoException(
                    "Could not create a field key for organisation " + organisationId, e);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }
}

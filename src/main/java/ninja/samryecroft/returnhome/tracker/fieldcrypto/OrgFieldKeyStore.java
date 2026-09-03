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
    private final SecureRandom secureRandom = new SecureRandom();

    public OrgFieldKeyStore(OrgFieldKeyRepository repository, KeyProvider keyProvider) {
        this.repository = repository;
        this.keyProvider = keyProvider;
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

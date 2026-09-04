package ninja.samryecroft.returnhome.tracker.document;

/**
 * Custody of the per-organisation key-encryption keys (KEKs) that wrap each document's data key.
 *
 * <p>This is deliberately the <em>only</em> seam between the envelope-encryption code and wherever
 * the keys actually live, so the Azure Key Vault implementation can be provisioned (WS-D) without
 * the crypto path changing. Implementations must:
 *
 * <ul>
 *   <li><strong>Fail closed.</strong> Any error - unreachable vault, denied RBAC, missing key -
 *       must raise {@link KeyUnavailableException} rather than returning a substitute key. Nothing
 *       upstream is allowed to fall back to plaintext.</li>
 *   <li><strong>Never mix organisations.</strong> {@link #unwrap} is given the owning organisation
 *       resolved independently of the request's access check, and must refuse a wrapped key that
 *       names a different organisation's KEK. That is what makes an application-layer scoping bug
 *       (DOCUMENT-ENCRYPTION-DESIGN.md threat T3) yield ciphertext rather than another org's
 *       report.</li>
 *   <li><strong>Keep the key version.</strong> {@link WrappedKey} records the exact key version
 *       used, so rotating an org's KEK re-wraps data keys only and never rewrites a stored
 *       document.</li>
 * </ul>
 */
public interface KeyProvider {

    /**
     * The key to wrap new data keys for this organisation with, creating it if this is the
     * organisation's first document and creation is enabled. Idempotent.
     *
     * @throws KeyUnavailableException if the key cannot be obtained or created
     */
    KeyHandle currentKeyFor(long organisationId);

    /**
     * Wraps a freshly generated data key. The plaintext data key must never be persisted or
     * logged; only the returned {@link WrappedKey} travels with the document.
     */
    WrappedKey wrap(KeyHandle handle, byte[] dataKey);

    /**
     * Unwraps a data key read back from a stored envelope.
     *
     * @param organisationId the organisation resolved to own the document, independently of the
     *                       access check - implementations cross-check it against
     *                       {@link WrappedKey#keyName()}
     * @throws KeyUnavailableException if the key store is unreachable or refuses the operation
     * @throws DocumentIntegrityException if the wrapped key does not belong to this organisation
     */
    byte[] unwrap(long organisationId, WrappedKey wrappedKey);

    /**
     * Whether this organisation's KEK already exists - <strong>a read, and only a read</strong>
     * (T168(b)).
     *
     * <p>{@link #currentKeyFor} cannot answer this question. Where key auto-creation is enabled it
     * CREATES the key on a miss, so using it as a probe mints the very thing it was asked about and
     * then truthfully reports success. This method has no create path in any implementation, which
     * is what makes it safe to call in either configuration - and it needs no privilege the
     * application does not already hold, since Key Vault Crypto User includes
     * {@code vaults/keys/read}.
     *
     * <p>It exists so that an organisation reaching ACTIVE is a VERIFIED FACT rather than a human's
     * assertion. A lifecycle status that says everything is fine when it isn't is worse than no
     * status at all: it is the original incident with a reassurance attached.
     *
     * <p><strong>Absent is not the same as unreachable, and implementations must not conflate
     * them.</strong> Only a definite "no such key" answers {@code false}; a vault outage, a denied
     * role or any other failure raises {@link KeyUnavailableException}. Collapsing the two would let
     * a transient outage read as "this organisation has no key", which on the activation path means
     * refusing an organisation that is in fact perfectly provisioned.
     *
     * @throws KeyUnavailableException if existence could not be determined
     */
    boolean keyExists(long organisationId);

    /** The Key Vault key-name convention, shared by every implementation so envelopes are portable. */
    static String keyNameFor(long organisationId) {
        return "org-" + organisationId + "-kek";
    }

    /**
     * Reads the organisation back out of a key name produced by {@link #keyNameFor(long)}.
     *
     * @return the organisation id, or {@code -1} if the name does not follow the convention (which
     *         is itself a mismatch, and must be treated as one)
     */
    static long organisationIdIn(String keyName) {
        if (keyName == null || !keyName.startsWith("org-") || !keyName.endsWith("-kek")) {
            return -1;
        }
        try {
            return Long.parseLong(keyName.substring(4, keyName.length() - 4));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

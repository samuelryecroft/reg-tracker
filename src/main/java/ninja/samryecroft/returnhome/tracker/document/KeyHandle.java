package ninja.samryecroft.returnhome.tracker.document;

/**
 * A specific version of one organisation's KEK, as returned by {@link KeyProvider#currentKeyFor}.
 *
 * @param organisationId the organisation this key belongs to
 * @param keyName        Key Vault key name, always {@link KeyProvider#keyNameFor(long)}
 * @param keyVersion     the resolved version, recorded in the envelope so rotation never
 *                       invalidates already-stored documents
 * @param wrapAlgorithm  the algorithm the provider will wrap with, recorded for the same reason
 */
public record KeyHandle(long organisationId, String keyName, String keyVersion, String wrapAlgorithm) {
}

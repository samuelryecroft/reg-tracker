package ninja.samryecroft.returnhome.tracker.document;

/**
 * What a successful store returns: the key to record on the report row, plus the KEK that wrapped
 * it. The key identity comes back so the audit row can name the exact key and version used, which
 * is what makes the trail reconcilable against Key Vault's own operation log.
 */
public record StoredDocumentRef(String storageKey, String keyName, String keyVersion, String wrapAlgorithm) {
}

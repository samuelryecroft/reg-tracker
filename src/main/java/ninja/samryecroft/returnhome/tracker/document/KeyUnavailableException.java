package ninja.samryecroft.returnhome.tracker.document;

/**
 * The key store could not be reached or refused the operation - vault down, network partition,
 * RBAC denied, key missing.
 *
 * <p>Distinct from {@link DocumentIntegrityException} because it is a <em>transient
 * infrastructure</em> fault: the right response on a download is 503 (try again later), not 500.
 * Either way the document is not served.
 */
public class KeyUnavailableException extends DocumentSecurityException {

    public KeyUnavailableException(String message) {
        super(message);
    }

    public KeyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

package ninja.samryecroft.returnhome.tracker.document;

/**
 * Base type for every failure on the encrypted-document path.
 *
 * <p>Its whole reason to exist is the fail-closed rule: callers catch <em>this</em>, never a
 * narrower cause, so no new failure mode can slip past and end up serving or storing plaintext.
 * Messages are deliberately free of key material, ciphertext and child data - they reach logs.
 */
public class DocumentSecurityException extends RuntimeException {

    public DocumentSecurityException(String message) {
        super(message);
    }

    public DocumentSecurityException(String message, Throwable cause) {
        super(message, cause);
    }
}

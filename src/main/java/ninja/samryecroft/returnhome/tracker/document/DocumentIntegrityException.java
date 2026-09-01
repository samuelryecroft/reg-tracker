package ninja.samryecroft.returnhome.tracker.document;

/**
 * The stored document did not authenticate: a bad AES-GCM tag, a malformed or missing envelope, or
 * a wrapped key naming a different organisation than the one that owns the document.
 *
 * <p>Unlike {@link KeyUnavailableException} this is never transient - retrying cannot help. It
 * means the bytes were altered, truncated, or came from somewhere they should not have, so it is
 * reported to the audit trail as a security event rather than a storage error.
 */
public class DocumentIntegrityException extends DocumentSecurityException {

    public DocumentIntegrityException(String message) {
        super(message);
    }

    public DocumentIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}

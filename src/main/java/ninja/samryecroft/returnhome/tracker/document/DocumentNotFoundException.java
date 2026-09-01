package ninja.samryecroft.returnhome.tracker.document;

/**
 * No object exists under the requested key. A separate type from the crypto failures because it is
 * a 404, not a security event - though it still never yields plaintext.
 */
public class DocumentNotFoundException extends DocumentSecurityException {

    public DocumentNotFoundException(String message) {
        super(message);
    }
}

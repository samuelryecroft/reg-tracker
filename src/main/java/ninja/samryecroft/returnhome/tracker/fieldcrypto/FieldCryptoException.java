package ninja.samryecroft.returnhome.tracker.fieldcrypto;

/**
 * Any failure to encrypt or decrypt a field. There is deliberately no variant that returns the
 * value unencrypted, and no caller is given the option to continue without it: a fallback is
 * exactly how plaintext special-category data about children ends up in a column that everyone
 * believes is encrypted.
 *
 * <p>This mirrors {@code DocumentSecurityException} in the WS-B document path rather than reusing
 * it, because the two travel to different places - a document failure becomes an HTTP status on one
 * download, while a field failure can surface anywhere a record is read.
 */
public class FieldCryptoException extends RuntimeException {

    public FieldCryptoException(String message) {
        super(message);
    }

    public FieldCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}

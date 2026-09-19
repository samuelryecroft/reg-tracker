package ninja.samryecroft.returnhome.tracker.core;

/**
 * The action was legitimate when the page was drawn and is not now: the record has moved on - a
 * report already approved, an interview no longer awaiting a time. Rendered as a 409 with this
 * message shown to the person, so it must say what state the record is in and, where useful, to
 * reload; never an identifier from a record.
 *
 * <p>Since T378 a bare {@code IllegalStateException} is a 500, which is right for a broken
 * invariant or a missing platform default and wrong for two people racing on one case.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}

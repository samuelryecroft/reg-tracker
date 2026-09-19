package ninja.samryecroft.returnhome.tracker.core;

/**
 * The thing the person asked for by identifier does not exist - or is not theirs to see, and the
 * two are deliberately indistinguishable. Rendered as a 404 with this message, which names the
 * identifier they typed and nothing from inside a record.
 *
 * <p>Throw this, never a bare {@code IllegalArgumentException}, from a lookup that fails. Since
 * T378 an untyped exception is a 500: an internal bug reported as "we can't find that page" is a
 * bug nobody looks for, and a scoping bug in a system of record must alarm, not reassure.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}

package ninja.samryecroft.returnhome.tracker.child;

/**
 * A young person's record is not finished, so it cannot be archived yet (T170).
 *
 * <p><strong>The message carries the COUNT and the ROUTE, not just a refusal.</strong> Oscar's
 * ruling: "1 interview is still awaiting review" - finish the record, then archive. A bare "cannot
 * archive" leaves the person guessing which of the four things on the screen is the obstacle, and
 * the obstacle is precisely the thing we want them to go and deal with.
 */
public class ChildNotArchivableException extends RuntimeException {

    public ChildNotArchivableException(String message) {
        super(message);
    }
}

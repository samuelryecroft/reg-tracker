package ninja.samryecroft.returnhome.tracker.user;

/**
 * The Entra directory object id on this form already belongs to another account.
 *
 * <p>A distinct type rather than a generic failure because it is the one save error an administrator
 * can actually fix, and the fix is on one field. {@code uq_users_idp_subject} makes it reachable
 * straight from the form, and left untranslated it arrives as a 500 - which tells the person nothing
 * and loses everything else they had typed.
 *
 * <p>It also means something specific: two application accounts were about to point at one directory
 * identity, so whichever signed in would be arbitrary. The constraint exists to stop exactly that.
 */
public class DuplicateObjectIdException extends RuntimeException {

    public DuplicateObjectIdException() {
        super("That Directory object ID is already recorded against another account.");
    }
}

package ninja.samryecroft.returnhome.tracker.user.dto;

/** Validation shared by the create and edit user forms, so the two cannot drift apart. */
public final class UserFormPatterns {

    /**
     * The shape of an Entra directory object id, as the portal renders it.
     *
     * <p>A format check on this field earns its place in a way it would not on, say, a phone number.
     * This is the one place in the system where a human transcribes an identifier, and a mistyped
     * one does not fail here - it fails at that person's first sign-in, with a message that
     * deliberately refuses to say why (telling "no account" apart from "account disabled" would
     * answer whether someone has an account on a children's safeguarding system). So the cost of
     * catching a paste error late is an administrator who cannot explain why a colleague is locked
     * out, and the cost of catching it here is a form error.
     *
     * <p>The empty alternative is load-bearing, not laziness: an unfilled text input binds as an
     * empty string rather than null, and the field is genuinely optional - the break-glass admin has
     * no directory identity, and an account can be created before anyone has looked the id up. A
     * pattern without it would make the field effectively mandatory.
     */
    public static final String OBJECT_ID =
            "^$|^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    public static final String OBJECT_ID_MESSAGE =
            "Enter the Directory object ID exactly as it appears in Entra, e.g. "
                    + "6f0a1c9e-3c2b-4c1a-9f77-0c0a1b2c3d4e";

    private UserFormPatterns() {
    }
}

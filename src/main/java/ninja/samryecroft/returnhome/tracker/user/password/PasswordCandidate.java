package ninja.samryecroft.returnhome.tracker.user.password;

/**
 * What {@link StrongPasswordValidator} needs from a form, so one constraint serves every form that
 * sets a password (T272 R4) instead of each growing its own copy of the rule.
 *
 * <p>Every method may return null. <strong>A form returns null for a value it genuinely does not
 * have, and says so where it implements this</strong> - never a placeholder, because an empty
 * context value matches every password and a made-up one bans a real word.
 */
public interface PasswordCandidate {

    /** The password being set, or null/blank when this submission is not setting one. */
    String passwordBeingSet();

    /** The field to report a violation against, so the message lands where the person is typing. */
    String passwordFieldName();

    /** The account's username, or null if this form cannot know it. */
    String usernameForPolicy();

    /** The account's email address; only its local-part is used. */
    String emailForPolicy();

    /** The organisation the account belongs to, resolved to a NAME by the validator. */
    Long organisationIdForPolicy();
}

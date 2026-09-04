package ninja.samryecroft.returnhome.tracker.organisation;

/**
 * An organisation cannot be activated because its per-organisation KEK does not exist yet
 * (T168(b)). Operational, not a bug: the remedy is provisioning the key, not retrying.
 *
 * <p>Carries the key name because the only person who sees this is an administrator on a privileged
 * screen who needs to know WHICH key to have created. That is the opposite of the end-user 503 on
 * the write path, which withholds it - telling an unauthenticated caller why a crypto operation
 * failed tells them how to probe it. Same fact, different audience, different answer.
 */
public class OrganisationNotActivatableException extends RuntimeException {

    private final String keyName;

    public OrganisationNotActivatableException(String keyName) {
        super("Cannot activate: " + keyName + " does not exist yet");
        this.keyName = keyName;
    }

    public String getKeyName() {
        return keyName;
    }
}

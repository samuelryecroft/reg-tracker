package ninja.samryecroft.returnhome.tracker.core;

/**
 * The request was understood and refused on its content - a role combination an account may not
 * hold, a comment that is required, a home that must be chosen. Rendered as a 400 with this
 * message shown to the person, so the message must be written for them: what was refused and what
 * to do instead, never an identifier from a record and never another person's details.
 *
 * <p>These belong on the form as field errors; until they are, this is the honest status. Since
 * T378 a bare {@code IllegalArgumentException} is a 500, which is right for a bug and wrong for a
 * refusal, so refusals carry this type.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}

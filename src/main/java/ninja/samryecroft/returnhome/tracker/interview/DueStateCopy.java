package ninja.samryecroft.returnhome.tracker.interview;

/**
 * The one place a due state's WORD is written (T165).
 *
 * <p>Creed's a11y sweep found that the badges and the group headings were separately worded, and
 * that the badges leaned on a glyph rather than a word to say which state they were in: strip
 * the U+25F7 and U+2713 prefixes and "6h 10m left" and "30h 5m left" are the same sentence, so a
 * screen reader had nothing but the character's NAME ("circle with upper right quadrant black") to
 * tell "under 24 hours to a statutory deadline" from "fine". The glyph now lives in aria-hidden markup,
 * which means the word has to carry the state - and the badge and the heading have to agree on it,
 * or the same request reads as two different states on one screen.
 *
 * <p>These are not invented strings: they are the words the group headings have always shipped
 * ("Overdue - statutory 72 hours passed", "Due soon - under 24 hours remaining", "On track"),
 * lifted to where the badge can reuse them. That is also the wording the human signed off for the
 * badges under T165c - "Due soon - 6h 10m left" and "On track - 30h 5m left", em dash, OVERDUE and
 * NO_CLOCK unchanged because they already read as their state. Pinned character-for-character by
 * {@code DeadlineTrackerTest#theSignedOffDeadlineWordingIsRenderedExactly}: this is copy on a
 * statutory surface, so a rewording should have to be a deliberate act, not a passing edit.
 */
public final class DueStateCopy {

    private DueStateCopy() {
    }

    /** The state as a word - never a glyph, never colour alone. */
    public static String stateWord(DueState state) {
        return switch (state) {
            case OVERDUE -> "Overdue";
            case DUE_SOON -> "Due soon";
            case ON_TRACK -> "On track";
            case NO_CLOCK -> "Return time not recorded";
        };
    }
}

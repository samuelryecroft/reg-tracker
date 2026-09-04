package ninja.samryecroft.returnhome.tracker.child;

/**
 * The one thing every screen that shows a child's name actually renders (spec §2.5): a single,
 * already-resolved projection, never both the masked and revealed strings at once.
 *
 * <p>Kevin's review (T138 masking design conversation) is deliberate about this shape: a record
 * carrying {@code masked}/{@code revealed} pairs would still put the child's real name into the
 * page - and therefore the page source, the browser cache, a screenshot, any HTML-capturing
 * telemetry - of a screen that is *displaying* as masked. "It was in the DOM but hidden" is a
 * materially weaker position than "it was never sent" for Article 9 data about children, and it
 * makes the masked view's disclosure surface identical to the revealed one. So the caller decides
 * the boolean once, {@link #of} resolves it into exactly one string per field, and the template
 * prints {@code identity.avatar()} / {@code identity.label()} and makes no decisions of its own.
 *
 * <p>This is a pure function of {@code (Child, boolean)} - no injection, no database, trivially
 * unit-testable - deliberately not a Spring bean and not a method on {@link Child} itself (a
 * child entity has no business knowing about a viewer's session-scoped reveal state).
 *
 * <p><strong>Masking is not cheaper.</strong> The initials are plaintext columns (V13), so those
 * cost nothing either way - but {@link Child#getLocalCaseReference()} is decrypted the moment the
 * entity is loaded (its {@code @Encrypted} field listener runs on load, not on display), so the
 * masked label costs exactly the same as the revealed one. Do not build a "masked lists skip the
 * crypto" optimisation on the opposite assumption, and do not describe the masked view anywhere as
 * the key-free one.
 *
 * <p><strong>Masking is not access control.</strong> Everyone who can see a page containing a
 * {@code ChildIdentity} is already authorised to see the full name - masking exists to reduce
 * shoulder-surfing and casual disclosure in a shared office, not to withhold data from the viewer.
 * Never pair this with padlock/shield/"protected" iconography; that would be a false claim about
 * what the control does.
 *
 * <p><strong>What the mask actually defeats, precisely</strong> (Kevin's review): the masked label
 * is initials plus the local case reference, and that reference is a stable identifier the
 * organisation itself already uses - a colleague, or another visiting professional, can resolve
 * "CH-0041" to a named child immediately. So masking defeats a <em>stranger's</em> glance (someone
 * in reception, a cleaner, a photograph of a screen); it does not defeat a colleague's. That is the
 * spec's shape, not a defect - initials alone would be ambiguous, and two children sharing initials
 * in one home is a safety problem (acting on the wrong child's record), not just a UX one. But say
 * this precisely rather than letting "names are masked" be read as hiding identity from everyone
 * who can see the screen.
 */
public record ChildIdentity(String avatar, String label) {

    private static final String MIDDLE_DOT = " · ";

    /**
     * @param child the child whose identity is being projected
     * @param revealed the viewer's resolved reveal state for the page being rendered - never
     *     stored, always computed fresh per request (see {@code NameRevealService})
     */
    public static ChildIdentity of(Child child, boolean revealed) {
        if (revealed) {
            return new ChildIdentity(rawInitials(child), child.getFullName());
        }
        return new ChildIdentity(punctuatedInitials(child), maskedLabel(child));
    }

    /** "AB" - no punctuation, so it reads as initials rather than an abbreviation of something. */
    private static String rawInitials(Child child) {
        String first = nullToEmpty(child.getFirstNameInitial());
        String last = nullToEmpty(child.getLastNameInitial());
        String initials = first + last;
        return initials.isEmpty() ? "?" : initials;
    }

    /** "A.B" - {@link Child#getInitials()} minus its own trailing dot ("A.B."). */
    private static String punctuatedInitials(Child child) {
        String initials = child.getInitials();
        return initials.endsWith(".") ? initials.substring(0, initials.length() - 1) : initials;
    }

    /**
     * "A.B. · CH-0041", or just "A.B." if no case reference is recorded yet - a child can exist in
     * this system before intake finishes assigning one.
     */
    private static String maskedLabel(Child child) {
        String reference = child.getLocalCaseReference();
        return reference == null || reference.isBlank()
                ? child.getInitials()
                : child.getInitials() + MIDDLE_DOT + reference;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

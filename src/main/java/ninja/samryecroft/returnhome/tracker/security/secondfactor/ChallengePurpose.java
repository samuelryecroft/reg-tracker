package ninja.samryecroft.returnhome.tracker.security.secondfactor;

/**
 * What a {@link LoginChallenge} was issued FOR (T353b).
 *
 * <p>Before this existed, a {@code login_challenges} row was purpose-blind: the verifier looked up
 * "the newest challenge for this user, whatever it was for", and issuing one consumed every other
 * live challenge for that user. Once a second flow (self-service password reset) also mints codes on
 * this table, that is two live defects at once - a SIGN-IN code would satisfy a RESET verifier and
 * vice versa, and starting a reset would silently kill a live sign-in code (T353 design §5). Neither
 * is visible to a test that exercises one flow at a time.
 *
 * <p>So the purpose is part of the lookup and of the consume-outstanding query: a code is only ever
 * found, spent, or retired within its own flow. The reset verifier narrows further still, requiring
 * the challenge id to be the one minted for that specific reset token - but that guard belongs to the
 * reset cards, not here; this enum only keeps the two flows from meeting.
 *
 * <p>Stored as {@code STRING}, so adding a future purpose needs no migration; removing one would make
 * existing rows unreadable, which is the right cost to pay attention to before deleting a constant.
 */
public enum ChallengePurpose {

    /** The emailed second factor between a correct password and an authenticated session (T322). */
    SIGN_IN,

    /** The emailed code that must verify before a self-service password reset is applied (T353). */
    PASSWORD_RESET
}

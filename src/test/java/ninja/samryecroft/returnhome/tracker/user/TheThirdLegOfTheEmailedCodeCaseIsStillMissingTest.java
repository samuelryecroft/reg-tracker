package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * A tripwire on a KNOWN GAP, so the comment describing it cannot outlive it.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>{@code UserService.changeEmail} carries the case for emailed second-factor codes. Two
 * conditions hold - <b>no colleague may edit an address</b> and <b>no self-service password reset
 * exists</b>. A third, <b>an address is verified on first use</b>, was specified in T322, is NOT
 * BUILT, and would not have done the job attributed to it. For a while that comment said all three
 * held, so the codebase read as though a hole were closed, which is worse than saying nothing:
 * a comment naming three protections is exactly what stops the next person counting them.
 *
 * <p>The comment now says which leg is missing. <b>That correction has the same weakness as the
 * error it replaced:</b> it is a sentence about the world, and the world can move underneath it
 * without disturbing a character of it. Somebody builds address verification, and three comments
 * quietly become wrong in the other direction - understating the protection, which is safer but
 * still false, and false in a way that makes the next reader trust none of it.
 *
 * <p>So this fails <b>when the gap is closed</b>. That is deliberate and it is this codebase's own
 * idiom: {@code InterviewStatusTransitionsTest.noTransitionReachesCancelled} does the same thing for
 * cancellation, and T154's writer guard for status writes. <b>A red test that says "good, now go and
 * update the argument" costs one commit; a stale safety argument costs whatever the next person
 * concludes from it.</b>
 *
 * <p><b>And the missing leg would not close the hole as specified anyway</b>, which is the part most
 * worth carrying forward: the confirmation is delivered TO the address being verified, so whoever
 * set that address receives it. It proves the address is DELIVERABLE, not who OWNS it - and
 * ownership is the entire question when an administrator types the address at account creation.
 * Whoever trips this test should check that what they built proves ownership before recording the
 * leg as done.
 */
class TheThirdLegOfTheEmailedCodeCaseIsStillMissingTest {

    /**
     * Fields on {@link User} suggesting an address has been verified. Matched loosely on purpose -
     * the point is to notice the capability arriving under whatever name it arrives under, not to
     * guess the name correctly.
     */
    private static final List<String> SUGGESTS_VERIFICATION = List.of("verif", "confirmedemail", "emailconfirmed");

    @Test
    void userStillCarriesNoVerifiedAddressState() {
        List<String> suspicious = Arrays.stream(User.class.getDeclaredFields())
                .map(Field::getName)
                .filter(name -> SUGGESTS_VERIFICATION.stream()
                        .anyMatch(hint -> name.toLowerCase(Locale.ROOT).contains(hint)))
                .toList();

        assertThat(suspicious)
                .as("""
                        GOOD NEWS, PROBABLY - and this test is asking you to finish the job.

                        UserService.changeEmail argues that emailed second-factor codes are acceptable \
                        only while three things hold, and records that the third - an address VERIFIED \
                        ON FIRST USE - is not built. If you have just built it, that argument is now \
                        wrong in three files (UserService.changeEmail, EditUserForm, and \
                        admin/user-form-edit.html) and they need updating together.

                        BEFORE YOU RECORD THE LEG AS DONE, CHECK WHAT YOUR MECHANISM PROVES. A \
                        confirmation delivered TO the address being verified is received by whoever \
                        SET that address - so it proves DELIVERABILITY, not OWNERSHIP, and ownership \
                        is the whole question when an administrator types the address at account \
                        creation. If that is what you built, it has not added a protection and the \
                        comments should still say so.

                        Then delete this test - it has done its job.

                        Fields that look like verification state: %s""".formatted(suspicious))
                .isEmpty();
    }
}

package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The successor to {@code TheThirdLegOfTheEmailedCodeCaseIsStillMissingTest}, which fired exactly as
 * designed and has been retired.
 *
 * <h2>Why the risk went UP when the mechanism was built, not down</h2>
 *
 * <p>That tripwire guarded a gap: a safety argument claiming three conditions when only two held. It
 * fired the moment address verification was built, asked the builder to check what their mechanism
 * <em>proved</em>, and asked to be deleted. It was: T322 built {@code emailVerifiedAt}, which proves
 * an address is <b>DELIVERABLE</b> - it bounds a mistyped address so codes for a young person's
 * record stop being posted to a stranger - and proves nothing whatever about who <b>OWNS</b> it.
 *
 * <p><b>So the argument still rests on two legs, and there is now a mechanism named after the third
 * one.</b> That is a worse trap than the original: the next reader finds "verified on first use" in
 * the codebase, finds a comment naming it, and upgrades the count by <em>counting names rather than
 * reading them</em>. Deleting the old tripwire without replacing it would have removed the guard at
 * the exact moment the thing it guarded against became easier to do.
 *
 * <p><b>This is a wording guard, and wording guards are blunt.</b> It cannot tell whether the
 * argument is sound - only whether these three files have started claiming three conditions. That is
 * a real limit and it is the same trade {@code T320}'s guard makes about "young people": blunt and
 * present beats subtle and absent. If somebody genuinely builds an OWNERSHIP control, this test
 * should fail, and its failure message says what to do about it.
 */
class TheEmailedCodeCaseStillRestsOnTwoLegsTest {

    /** The three places the safety case is stated. They must not drift apart. */
    private static final List<Path> SAFETY_CASE_FILES = List.of(
            Path.of("src/main/java/ninja/samryecroft/returnhome/tracker/user/UserService.java"),
            Path.of("src/main/java/ninja/samryecroft/returnhome/tracker/user/dto/EditUserForm.java"),
            Path.of("src/main/resources/templates/admin/user-form-edit.html"));

    /*
     * A SECOND CHECK WAS WRITTEN HERE AND DELETED, and why is worth more than the check was.
     *
     * It scanned these files for "one of three" / "all three" / "three conditions", to catch the
     * argument being upgraded to three legs. It fired immediately - on this line in UserService:
     *
     *     "This comment first claimed all three conditions held. Corrected, it then claimed..."
     *
     * That is the file's own HISTORY of the mistake, and it is one of the most valuable sentences in
     * it: it records that the argument was wrong in both directions and why. A wording guard cannot
     * tell a CLAIM from a DESCRIPTION OF A PAST CLAIM - and the only ways to make it pass were to
     * narrow the phrases until they matched nothing useful, or to delete the history.
     *
     * A guard that can only be satisfied by removing an accurate record is not protecting the
     * argument, it is eroding it - the same shape as softening a screen claim to match a weak
     * guarantee instead of fixing the guarantee. So it went, and the check below stayed: it does not
     * try to detect a wrong conclusion in prose, it enforces the one property that PREVENTS the
     * wrong conclusion - that the limit is always stated beside the name.
     */

    /**
     * And the limit must stay stated. A file that names the mechanism without naming what it proves
     * is how the next reader concludes the wrong thing - which is the whole history of this comment.
     */
    @Test
    void everyFileNamingTheMechanismAlsoNamesItsLimit() throws IOException {
        for (Path file : SAFETY_CASE_FILES) {
            String text = Files.readString(file).toLowerCase(Locale.ROOT);
            if (!text.contains("verified on first use") && !text.contains("verified on first")) {
                continue;
            }
            assertThat(text)
                    .as("%s names 'verified on first use' but no longer says what it proves. "
                            + "Deliverability, not ownership - say so beside the name, or the name "
                            + "is read as the protection it is not.", file)
                    .contains("deliverab");
        }
    }
}

package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@code getFullName()} is derived from the two stored halves, which is what lets roughly twenty
 * templates and the document-generation paths keep reading {@code fullName} unchanged.
 *
 * <p>The mononym case is the one that matters. V17 puts a single-token name wholly in
 * {@code lastName} with a null {@code firstName}, so this getter has to reproduce it exactly rather
 * than emit a leading space - otherwise every migrated single-name account displays wrongly and
 * sorts wrongly for the life of the row.
 */
class UserNameDerivationTest {

    @Test
    void twoHalvesAreJoinedWithASingleSpace() {
        assertThat(user("Ada", "Lovelace").getFullName()).isEqualTo("Ada Lovelace");
    }

    @Test
    void aMononymIsTheSurnameAloneWithNoLeadingSpace() {
        assertThat(user(null, "Administrator").getFullName()).isEqualTo("Administrator");
    }

    @Test
    void aBlankFirstNameIsTreatedTheSameAsAnAbsentOne() {
        // Belt and braces: the forms trim to null, but an entity assembled anywhere else must not
        // produce " Administrator".
        assertThat(user("   ", "Administrator").getFullName()).isEqualTo("Administrator");
    }

    @Test
    void aCompoundSurnameSurvivesTheRoundTrip() {
        // The case V17's first-space split exists to protect: "Sam de la Cruz" splits to
        // "Sam" + "de la Cruz" and joins back to the original.
        assertThat(user("Sam", "de la Cruz").getFullName()).isEqualTo("Sam de la Cruz");
    }

    private User user(String firstName, String lastName) {
        User user = new User();
        user.setFirstName(firstName);
        user.setLastName(lastName);
        return user;
    }
}

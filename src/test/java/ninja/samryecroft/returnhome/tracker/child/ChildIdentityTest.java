package ninja.samryecroft.returnhome.tracker.child;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * T138 1c: the whole masking decision lives in {@link ChildIdentity#of}, so this is where it's
 * tested - a pure function of {@code (Child, boolean)}, no Spring context needed. Kevin's review
 * gave the exact worked example this pins: masked -&gt; avatar "A.B", label "A.B. · CH-0041";
 * revealed -&gt; avatar "AB", label "Alex Brennan".
 */
class ChildIdentityTest {

    private static Child childNamed(String first, String last, String caseReference) {
        Child child = new Child();
        child.setFirstName(first);
        child.setLastName(last);
        child.setLocalCaseReference(caseReference);
        return child;
    }

    @Test
    void maskedShowsPunctuatedInitialsAndTheCaseReference() {
        Child child = childNamed("Alex", "Brennan", "CH-0041");

        ChildIdentity identity = ChildIdentity.of(child, false);

        assertThat(identity.avatar()).isEqualTo("A.B");
        assertThat(identity.label()).isEqualTo("A.B. · CH-0041");
    }

    @Test
    void revealedShowsPlainInitialsAndTheFullName() {
        Child child = childNamed("Alex", "Brennan", "CH-0041");

        ChildIdentity identity = ChildIdentity.of(child, true);

        assertThat(identity.avatar()).isEqualTo("AB");
        assertThat(identity.label()).isEqualTo("Alex Brennan");
    }

    @Test
    void revealedNeverMentionsTheCaseReference() {
        // Kevin's shape is deliberate: never both strings, and the revealed projection carries no
        // trace of the masked one - if this ever changed to include the case reference "for
        // context", that would be exactly the kind of drift the record exists to prevent.
        Child child = childNamed("Alex", "Brennan", "CH-0041");

        ChildIdentity identity = ChildIdentity.of(child, true);

        assertThat(identity.label()).doesNotContain("CH-0041");
    }

    @Test
    void maskedWithNoCaseReferenceYetOmitsTheMiddleDotEntirely() {
        // A child can exist before intake finishes assigning a local case reference.
        Child child = childNamed("Alex", "Brennan", null);

        ChildIdentity identity = ChildIdentity.of(child, false);

        assertThat(identity.label()).isEqualTo("A.B.");
    }

    @Test
    void maskedWithABlankCaseReferenceAlsoOmitsTheMiddleDot() {
        Child child = childNamed("Alex", "Brennan", "   ");

        ChildIdentity identity = ChildIdentity.of(child, false);

        assertThat(identity.label()).isEqualTo("A.B.");
    }

    @Test
    void aSingleRecordedNameStillProducesAUsableMaskedAvatar() {
        // Only a first name recorded: Child.getInitials() would render "A." alone.
        Child child = childNamed("Alex", "", "CH-0041");

        ChildIdentity identity = ChildIdentity.of(child, false);

        assertThat(identity.avatar()).isEqualTo("A");
    }

    @Test
    void aSingleRecordedNameStillProducesAUsableRevealedAvatar() {
        Child child = childNamed("Alex", "", "CH-0041");

        ChildIdentity identity = ChildIdentity.of(child, true);

        assertThat(identity.avatar()).isEqualTo("A");
    }

    /**
     * S-1: on a case card the disc already shows the initials, so the label beside it carries the
     * case reference alone rather than repeating them - "A.B" + "CH-0041", never "A.B" + "A.B. ·
     * CH-0041". Both sit inside the card's one link, so the accessible name still says both.
     */
    @Test
    void besideAnAvatarTheMaskedLabelIsTheCaseReferenceAloneAndNeverRepeatsTheInitials() {
        ChildIdentity identity = ChildIdentity.of(childNamed("Alex", "Brennan", "CH-0041"), false);

        assertThat(identity.besideAvatar()).isEqualTo("CH-0041");
        assertThat(identity.besideAvatar()).doesNotContain(identity.avatar());
        // The un-disced projection is untouched: this is a second projection, not a replacement.
        assertThat(identity.label()).isEqualTo("A.B. · CH-0041");
    }

    /**
     * A child can exist here before intake assigns a reference. Dropping to the reference alone
     * would then leave a card whose only text is a disc, naming nobody - so it falls back to the
     * ordinary masked label rather than to nothing.
     */
    @Test
    void besideAnAvatarWithNoCaseReferenceItFallsBackToTheOrdinaryMaskedLabel() {
        assertThat(ChildIdentity.of(childNamed("Alex", "Brennan", null), false).besideAvatar())
                .isEqualTo("A.B.");
        assertThat(ChildIdentity.of(childNamed("Alex", "Brennan", "  "), false).besideAvatar())
                .isEqualTo("A.B.");
    }

    /** Revealed, a card names the child exactly as every other screen does. */
    @Test
    void revealedTheCardLabelIsTheFullNameLikeTheOrdinaryOne() {
        ChildIdentity identity = ChildIdentity.of(childNamed("Alex", "Brennan", "CH-0041"), true);

        assertThat(identity.besideAvatar()).isEqualTo("Alex Brennan").isEqualTo(identity.label());
    }
}

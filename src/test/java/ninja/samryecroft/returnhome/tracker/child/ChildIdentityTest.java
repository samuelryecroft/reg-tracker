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
}

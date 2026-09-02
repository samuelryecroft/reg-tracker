package ninja.samryecroft.returnhome.tracker.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The role matrix, and the reason it is a ceiling rather than a default.
 *
 * <p>Reading a record and extracting it as a portable file are different acts. These tests pin which
 * roles may ever perform the second one, so that granting the export flag can never quietly widen
 * beyond what was agreed - the flag decides <em>who among the eligible</em>, never <em>whether</em>.
 */
class ExportCapabilityTest {

    private AppUserPrincipal principalWith(Role role, boolean canExport) {
        User user = new User();
        user.setRoles(Set.of(role));
        user.setCanExport(canExport);
        return new AppUserPrincipal(user);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "ORG_ADMIN", "COORDINATOR", "VIEWER"})
    void eligibleRolesCanExportOnceGranted(Role role) {
        assertThat(ExportCapability.canExport(principalWith(role, true))).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"HOME_STAFF", "VISITOR", "REVIEWER"})
    void ineligibleRolesCanNeverExportEvenIfTheFlagIsSet(Role role) {
        // The point of the ceiling. HOME_STAFF can already read and download one approved report for
        // their own home, but a bundled file of a child's entire history is a different act and not
        // part of that job; VISITOR and REVIEWER are scoped to their own work, not a whole history.
        // An administrator setting the flag by mistake must not be able to grant any of that.
        assertThat(ExportCapability.canExport(principalWith(role, true))).isFalse();
        assertThat(ExportCapability.isEligibleRole(principalWith(role, true))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "ORG_ADMIN", "COORDINATOR", "VIEWER"})
    void eligibilityAloneIsNotEnough(Role role) {
        // Extraction is granted deliberately, per account - which is what lets an organisation give
        // it to a named safeguarding lead rather than to everyone who can already read.
        assertThat(ExportCapability.canExport(principalWith(role, false))).isFalse();
        assertThat(ExportCapability.isEligibleRole(principalWith(role, false))).isTrue();
    }

    @Test
    void anAnonymousPrincipalCannotExport() {
        assertThat(ExportCapability.canExport(null)).isFalse();
        assertThat(ExportCapability.isEligibleRole(null)).isFalse();
    }

    @Test
    void theFlagDefaultsOff() {
        // Matches the V12 migration's default. A capability that arrives switched on is not one an
        // organisation has decided to grant.
        assertThat(new User().isCanExport()).isFalse();
    }
}

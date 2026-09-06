package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * T270, the human's report: <b>"Users screen shows chips for permissions but this causes the orgs
 * to shift depending on the number of permissions."</b>
 *
 * <p>The defect was never the chips - it was that {@code .case-tags}' {@code flex: none} sizes to
 * its own content, so a row with four role chips is wider than a row with one, and every flex
 * sibling after it (the org/home column a person actually scans down the page) starts further
 * right. Fixed with a {@code max-width} on {@code .role-chips} so overflow wraps to a second line
 * inside a fixed-width column instead of widening the column.
 *
 * <p>Asserted at the rendered geometry, not the CSS declaration: a row with one role chip and a row
 * with four must show the SAME {@code .case-aside} left edge. A CSS-property assertion (e.g. "does
 * {@code .role-chips} have a max-width") could pass while the actual pixel position still moved,
 * if the value chosen didn't bind the way intended.
 */
class UserListRoleChipsUiTest extends AbstractUiTest {

    private static final String PASSWORD = "role-chips-ui-test-password";

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User createUser(String username, Organisation org, Role... roles) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setLastName("Role Chips Tester " + username);
        user.setRoles(Set.of(roles));
        user.setOrganisation(org);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    @Test
    void theOrgColumnStartsAtTheSameXWhetherAUserHasOneRoleOrFour() {
        Organisation supplier = seededSupplier();
        createUser("role-chips-one", supplier, Role.VIEWER);
        // The org-scoped roles Role#isOrgScoped names as freely combinable - the realistic worst
        // case for chip count on one account.
        createUser("role-chips-four", supplier, Role.ORG_ADMIN, Role.COORDINATOR, Role.VISITOR, Role.REVIEWER);

        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.navigate(url("/admin/users"));
        page.waitForSelector(".case-list");

        double oneRoleAsideX = xOf(rowFor("role-chips-one"));
        double fourRoleAsideX = xOf(rowFor("role-chips-four"));

        assertThat(fourRoleAsideX)
                .as("the org column's horizontal position must not depend on how many role chips "
                        + "the row above it happens to have")
                .isCloseTo(oneRoleAsideX, org.assertj.core.data.Offset.offset(1.0));
    }

    private com.microsoft.playwright.Locator rowFor(String username) {
        return page.locator(".case-list .case", new com.microsoft.playwright.Page.LocatorOptions()
                .setHasText(username));
    }

    private double xOf(com.microsoft.playwright.Locator row) {
        return ((Number) row.locator(".case-aside")
                .evaluate("el => el.getBoundingClientRect().x")).doubleValue();
    }
}

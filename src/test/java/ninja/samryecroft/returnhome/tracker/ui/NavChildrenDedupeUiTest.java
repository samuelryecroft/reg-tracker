package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * T132: a HOME_STAFF+VIEWER (or HOME_STAFF+care-provider-ORG_ADMIN) account used to satisfy both
 * the old "My Children" and "Children" nav branches at once - two links to the same URL, and once
 * {@code aria-current} landed (T138 1a) two simultaneous "current page" announcements in one nav
 * (Creed's review). Fixed by collapsing to one link ({@code GlobalControllerAdvice#childrenNav} /
 * {@code RoleMatrix#isChildrenListPersonalisedToOwnHomes}) - this drives a real stacked-role
 * account through the real shell to prove the DOM, not just the role-matrix logic
 * ({@code RoleMatrixTest} covers that in isolation).
 */
class NavChildrenDedupeUiTest extends AbstractUiTest {

    private static final String PASSWORD = "nav-dedupe-test-password";

    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Home home;

    @BeforeEach
    void seedData() {
        Organisation careProviderOrg = seededCareProvider();
        home = new Home();
        home.setName("Nav Dedupe House");
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);
    }

    private void createStackedUser(String username, Role... roles) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setLastName("Nav Dedupe Tester");
        user.setRoles(Set.of(roles));
        user.setHomes(new HashSet<>(Set.of(home)));
        user.setEnabled(true);
        userRepository.save(user);
    }

    @Test
    void aHomeStaffAndViewerAccountSeesExactlyOneChildrenLinkLabelledForTheBroaderScope() {
        createStackedUser("nav-dedupe-staff-viewer", Role.HOME_STAFF, Role.VIEWER);
        login("nav-dedupe-staff-viewer", PASSWORD);
        page.navigate(url("/children"));
        page.waitForSelector(".shell-side");

        // Exactly one link to /children in the whole nav - the bug was ever having two.
        assertThat(page.locator(".shell-nav a[href='/children']").count()).isEqualTo(1);

        // VIEWER outranks the home-staff fallback in ChildController#list, so the label says so.
        assertThat(page.locator(".shell-nav a[href='/children']").textContent().trim()).isEqualTo("Children");

        // Exactly one "current page" announcement, not two.
        assertThat(page.locator(".shell-nav a[aria-current='page']").count()).isEqualTo(1);
        assertThat(page.locator(".shell-nav a[href='/children']").getAttribute("aria-current"))
                .isEqualTo("page");
    }

    @Test
    void aPureHomeStaffAccountStillGetsTheOwnHomesFraming() {
        createStackedUser("nav-dedupe-staff-only", Role.HOME_STAFF);
        login("nav-dedupe-staff-only", PASSWORD);
        page.navigate(url("/children"));
        page.waitForSelector(".shell-side");

        assertThat(page.locator(".shell-nav a[href='/children']").count()).isEqualTo(1);
        assertThat(page.locator(".shell-nav a[href='/children']").textContent().trim())
                .isEqualTo("My Children");
    }
}

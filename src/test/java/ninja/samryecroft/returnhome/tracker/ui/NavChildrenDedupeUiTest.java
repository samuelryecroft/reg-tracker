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

    /**
     * T320/T250: the rail no longer distinguishes scope - it says the same thing to everyone - so
     * the broader-scope claim is asserted where the ruling moved it, on the heading. The dedupe half
     * (one link, one aria-current) is T132's actual defect and is untouched.
     */
    @Test
    void aHomeStaffAndViewerAccountSeesOneChildrenLinkAndTheBroaderScopeOnTheHeading() {
        createStackedUser("nav-dedupe-staff-viewer", Role.HOME_STAFF, Role.VIEWER);
        login("nav-dedupe-staff-viewer", PASSWORD);
        page.navigate(url("/children"));
        page.waitForSelector(".shell-side");

        // Exactly one link to /children in the whole nav - the bug was ever having two.
        assertThat(page.locator(".shell-nav a[href='/children']").count()).isEqualTo(1);

        // The rail names, it no longer scopes: same label for every role that can reach this page.
        assertThat(page.locator(".shell-nav a[href='/children']").textContent().trim())
                .isEqualTo("Young people");

        // VIEWER outranks the home-staff fallback in ChildController#list, and the HEADING is where
        // that now shows: no "in your homes", because this account is not scoped to its own homes.
        // Asserted against the other test's heading, so the pair still proves the two scopes differ.
        assertThat(page.locator("main h1").textContent().trim()).isEqualTo("Young people");

        // Exactly one "current page" announcement, not two.
        assertThat(page.locator(".shell-nav a[aria-current='page']").count()).isEqualTo(1);
        assertThat(page.locator(".shell-nav a[href='/children']").getAttribute("aria-current"))
                .isEqualTo("page");
    }

    /**
     * T320/T250 moved this framing, it did not delete it. A nav item NAMES and a page heading can
     * EXPLAIN, so the rail now says the same thing to everyone and "in your homes" - the part that
     * says WHY this list is shorter - is on the heading, where there is room to read it. Both halves
     * are asserted here rather than only the new one, because dropping the second assertion would
     * turn a relocation into a silent deletion and nothing would have gone red.
     */
    @Test
    void aPureHomeStaffAccountStillGetsTheOwnHomesFramingOnTheHeadingNotTheRail() {
        createStackedUser("nav-dedupe-staff-only", Role.HOME_STAFF);
        login("nav-dedupe-staff-only", PASSWORD);
        page.navigate(url("/children"));
        page.waitForSelector(".shell-side");

        assertThat(page.locator(".shell-nav a[href='/children']").count()).isEqualTo(1);
        assertThat(page.locator(".shell-nav a[href='/children']").textContent().trim())
                .isEqualTo("Young people");
        assertThat(page.locator("main h1").textContent().trim())
                .isEqualTo("Young people in your homes");
    }
}

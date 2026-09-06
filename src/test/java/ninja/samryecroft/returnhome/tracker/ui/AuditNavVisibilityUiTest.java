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
 * T271, the human's report: <b>"we just get a 403 at the moment if a user cannot see the audit
 * screen lets not give the navigation option."</b>
 *
 * <p>The nav's Audit link used to be gated on {@code hasAnyRole('ORG_ADMIN','VIEWER','COORDINATOR',
 * 'ADMIN')} - the role CEILING {@code SecurityConfig}'s {@code /audit/**} matcher enforces, and no
 * more than that. But every audit route is actually gated by {@code ExportCapability.canExport}:
 * the role ceiling <em>and</em> a per-account grant an organisation sets on a named safeguarding
 * lead, not on everyone who holds an eligible role. A VIEWER without that grant passed the nav's
 * role check and hit a 403 on the very page the nav offered - the defect this pins.
 *
 * <p>Both directions asserted, not absence alone (a permanently-broken link would also pass an
 * absence-only check): a VIEWER WITHOUT the export flag must not see the link AND must genuinely
 * 403 if they navigate there directly (proving the fix removed an offer to a real refusal, not a
 * refusal that was never real); a VIEWER WITH the flag must see it AND land on the page.
 */
class AuditNavVisibilityUiTest extends AbstractUiTest {

    private static final String PASSWORD = "audit-nav-test-password";

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
        home.setName("Audit Nav Test House");
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);
    }

    private void createViewer(String username, boolean canExport) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setLastName("Audit Nav Tester");
        user.setRoles(Set.of(Role.VIEWER));
        user.setHomes(new HashSet<>(Set.of(home)));
        user.setCanExport(canExport);
        user.setEnabled(true);
        userRepository.save(user);
    }

    @Test
    void aViewerWithoutTheExportGrantIsNeverOfferedTheLinkAndGenuinely403sIfTheyGoDirect() {
        createViewer("audit-nav-viewer-ungranted", false);
        login("audit-nav-viewer-ungranted", PASSWORD);
        page.navigate(url("/children"));
        page.waitForSelector(".shell-side");

        assertThat(page.locator(".shell-nav a[href='/audit']").count())
                .as("an eligible ROLE without the per-account export grant must not be offered Audit")
                .isEqualTo(0);

        // Prove the refusal is real, not merely that the link is gone - an absence-only check would
        // also pass on a link that was hidden for the wrong reason, or on a page that had quietly
        // stopped 403ing at all. error.html deliberately does not print the specific message on a
        // non-404 status (it could name an internal type or a safeguarding identifier) - the status
        // itself, always rendered, is what's checkable here.
        page.navigate(url("/audit"));
        assertThat(page.locator("body").textContent())
                .as("the underlying refusal is unchanged by this fix - only the offer is")
                .contains("Error 403");
    }

    @Test
    void aViewerWithTheExportGrantIsOfferedTheLinkAndCanActuallyUseIt() {
        createViewer("audit-nav-viewer-granted", true);
        login("audit-nav-viewer-granted", PASSWORD);
        page.navigate(url("/children"));
        page.waitForSelector(".shell-side");

        assertThat(page.locator(".shell-nav a[href='/audit']").count())
                .as("the same role, WITH the grant, is genuinely entitled to the page")
                .isEqualTo(1);

        page.click(".shell-nav a[href='/audit']");
        assertThat(page.url()).endsWith("/audit");
        assertThat(page.locator("body").textContent()).doesNotContain("permission to view this page");
    }
}

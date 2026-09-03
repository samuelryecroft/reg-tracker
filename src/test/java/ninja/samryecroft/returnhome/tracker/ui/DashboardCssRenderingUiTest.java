package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

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
 * T93 hotfix: the FrontendSourceGuardTest catches the conflict-marker regression structurally
 * (source-level), but the whole point of the bug (Creed's review, then confirmed) was that the
 * CSS parsed fine and nothing threw - only the actual computed styles were wrong. This drives a
 * real headless Chrome against the real dashboard to check the same things Creed checked:
 * {@code .tiles} must be a grid, not a stacked block, and {@code .num} must be the large 30px
 * figure, not 16px body text.
 */
class DashboardCssRenderingUiTest extends AbstractUiTest {

    private static final String PASSWORD = "CorrectHorse123!";

    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void seedData() {
        // organisations is deliberately never truncated between test classes (see
        // AbstractIntegrationTest) - other tests index into it with .get(0), so reuse the seeded
        // reference organisation rather than inserting a new one that would permanently shift
        // that ordering for every test that runs afterwards in this JVM.
        Organisation careProvider = seededCareProvider();

        Home home = new Home();
        home.setName("CSS Render Test Home");
        home.setOrganisation(careProvider);
        homeRepository.save(home);

        User orgAdmin = new User();
        orgAdmin.setUsername("css-render-orgadmin");
        orgAdmin.setPassword(passwordEncoder.encode(PASSWORD));
        orgAdmin.setFullName("CSS Render Test Admin");
        orgAdmin.setRoles(Set.of(Role.ORG_ADMIN));
        orgAdmin.setOrganisation(careProvider);
        orgAdmin.setEnabled(true);
        userRepository.save(orgAdmin);
    }

    @Test
    void tilesRenderAsAStyledGridNotUnstyledStackedBlocks() {
        login("css-render-orgadmin", PASSWORD);
        page.navigate(url("/dashboard"));
        page.waitForSelector(".tiles");

        String tilesDisplay = (String) page.locator(".tiles").first()
                .evaluate("el => getComputedStyle(el).display");
        assertThat(tilesDisplay).isEqualTo("grid");

        String tileBorderWidth = (String) page.locator(".tile").first()
                .evaluate("el => getComputedStyle(el).borderTopWidth");
        assertThat(tileBorderWidth).isNotEqualTo("0px");

        String numFontSize = (String) page.locator(".tile .num").first()
                .evaluate("el => getComputedStyle(el).fontSize");
        assertThat(numFontSize).isEqualTo("30px");

        String zoneBorderWidth = (String) page.locator(".zone").first()
                .evaluate("el => getComputedStyle(el).borderTopWidth");
        assertThat(zoneBorderWidth).isNotEqualTo("0px");
    }

    @Test
    void narrowViewportShowsTheCardStackNotAVanishedTable() {
        login("css-render-orgadmin", PASSWORD);
        page.setViewportSize(600, 900);
        page.navigate(url("/dashboard"));
        // .table-wrap.responsive is legitimately display:none at this width - wait on .stack,
        // which is the element that's actually visible below the 720px breakpoint, instead.
        page.waitForSelector(".stack");

        String tableWrapDisplay = (String) page.locator(".table-wrap.responsive").first()
                .evaluate("el => getComputedStyle(el).display");
        assertThat(tableWrapDisplay).isEqualTo("none");

        String stackDisplay = (String) page.locator(".stack").first()
                .evaluate("el => getComputedStyle(el).display");
        assertThat(stackDisplay).isEqualTo("flex");
    }
}

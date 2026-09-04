package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
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
 * T138 1c: the shell header's reveal control, driven exactly the way a real user would (a click, a
 * navigation) rather than asserted against the raw response body - see
 * {@code NameRevealFlowIntegrationTest} for the server-side one-shot/audit assertions this
 * complements.
 */
class NameRevealUiTest extends AbstractUiTest {

    private static final String PASSWORD = "ui-reveal-test-password";

    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void seedData() {
        Organisation careProviderOrg = seededCareProvider();
        Home home = new Home();
        home.setName("Reveal UI House");
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Robin");
        child.setLastName("Ashworth");
        child.setDateOfBirth(LocalDate.of(2011, 6, 15));
        child.setLocalCaseReference("CH-UIREVEAL");
        child.setHome(home);
        childRepository.save(child);

        User staff = new User();
        staff.setUsername("ui-reveal-staff");
        staff.setPassword(passwordEncoder.encode(PASSWORD));
        staff.setLastName("UI Reveal Staff");
        staff.setRoles(Set.of(Role.HOME_STAFF));
        staff.setHomes(new HashSet<>(Set.of(home)));
        staff.setEnabled(true);
        userRepository.save(staff);
    }

    @Test
    void thePageStartsMaskedAndTheButtonNamesBothTheStateAndTheAction() {
        login("ui-reveal-staff", PASSWORD);
        page.navigate(url("/children"));
        page.waitForSelector(".shell-side");

        assertThat(page.locator("main").textContent()).contains("R.A. · CH-UIREVEAL");
        assertThat(page.locator("main").textContent()).doesNotContain("Robin Ashworth");

        // Spec §2.5: the accessible name must state what the control does and what it affects, not
        // just the visible word - same WCAG 2.5.3 reasoning as the appearance toggle.
        String accessibleText = page.locator(".shell-reveal-toggle").textContent()
                .replaceAll("\\s+", " ").trim();
        assertThat(accessibleText).isEqualTo("Reveal names on this page");
    }

    @Test
    void clickingRevealShowsTheFullNameThenHideCoversItAgainWithoutLeavingThePage() {
        login("ui-reveal-staff", PASSWORD);
        page.navigate(url("/children"));
        page.waitForSelector(".shell-side");

        page.click(".shell-reveal-toggle");
        page.waitForLoadState();

        assertThat(page.url()).endsWith("/children");
        // children/list.html has its own always-visible "Case reference" column, independent of
        // masking (the reference was never the secret - only the given/family name is), so this
        // only checks the NAME column switched to the full name, not that the reference vanished.
        assertThat(page.locator("main").textContent()).contains("Robin Ashworth");

        // Creed's review: a permanently disabled "revealed" button has no way back short of
        // leaving the page - on a part-filled form that would cost someone their place. The
        // control must be a real toggle, so "Hide" is live and clicking it covers the screen
        // again on the SAME page, no navigation away required.
        String hideAccessibleText = page.locator(".shell-reveal-toggle").textContent()
                .replaceAll("\\s+", " ").trim();
        assertThat(hideAccessibleText).isEqualTo("Hide names on this page");

        page.click(".shell-reveal-toggle");
        page.waitForLoadState();

        assertThat(page.url()).endsWith("/children");
        assertThat(page.locator("main").textContent()).contains("R.A. · CH-UIREVEAL");
        assertThat(page.locator("main").textContent()).doesNotContain("Robin Ashworth");

        // And a fresh page load without touching the control at all is masked too - hiding didn't
        // need to arm anything, because nothing was armed to begin with by the time this render
        // happened (NameRevealService: the flag is one-shot and was already gone).
        page.reload();
        page.waitForSelector(".shell-side");
        assertThat(page.locator("main").textContent()).contains("R.A. · CH-UIREVEAL");
        assertThat(page.locator("main").textContent()).doesNotContain("Robin Ashworth");
    }
}

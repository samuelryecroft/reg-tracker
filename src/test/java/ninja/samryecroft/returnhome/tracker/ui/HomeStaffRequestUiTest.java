package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

class HomeStaffRequestUiTest extends AbstractUiTest {

    private static final String PASSWORD = "home-staff-test-password";

    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private OrganisationRepository organisationRepository;

    @BeforeEach
    void seedData() {
        Organisation careProviderOrg = organisationRepository.findByTypeOrderByName(OrgType.CARE_PROVIDER).get(0);
        Home home = new Home();
        home.setName("UI Test House");
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Morgan");
        child.setLastName("Taylor");
        child.setDateOfBirth(LocalDate.of(2011, 6, 15));
        child.setHome(home);
        childRepository.save(child);

        User staff = new User();
        staff.setUsername("ui-home-staff");
        staff.setPassword(passwordEncoder.encode(PASSWORD));
        staff.setFullName("UI Test Staff");
        staff.setRoles(Set.of(Role.HOME_STAFF));
        staff.setHome(home);
        staff.setEnabled(true);
        userRepository.save(staff);
    }

    @Test
    void homeStaffCanRaiseARequestAndSeeItsDetail() {
        login("ui-home-staff", PASSWORD);

        page.click("text=Raise new request");
        page.waitForURL(url("/requests/new"));

        page.selectOption("#childId", new com.microsoft.playwright.options.SelectOption().setLabel("Morgan Taylor"));
        page.fill("#returnedAt", "2026-07-16T20:30");
        // Not "button[type=submit]" - that also matches the nav's "Log out" button, and Playwright's
        // page.click() acts on the first DOM match (the nav, which renders before <main>), not this one.
        page.click("text=Submit request");
        page.waitForLoadState();

        assertThat(page.url()).matches(".*/interview-requests/\\d+$");
        assertThat(page.getByText("Morgan Taylor").first().isVisible()).isTrue();
        assertThat(page.locator("span.status", new com.microsoft.playwright.Page.LocatorOptions().setHasText("Requested"))
                .isVisible()).isTrue();
    }
}

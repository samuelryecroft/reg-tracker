package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.options.SelectOption;
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
 * T377 (CODE-REVIEW-2026-09-18 P0.2): the audit CSV export button, pressed on the form the server
 * actually renders, produces an export.
 *
 * <p>Until T377 that form was written with a plain {@code action=} rather than {@code th:action},
 * so Thymeleaf never injected the CSRF hidden input and {@code CsrfFilter} refused every submission
 * with a 403 - for every user, since the day the form was written. The MockMvc tests on this route
 * post {@code .with(csrf())}, which synthesises the token the rendered form never carried, and the
 * one browser test on this page only followed the nav link. This test is the missing one: it
 * submits the rendered form through a real browser, so it fails whenever the token is not there.
 */
class AuditExportFormUiTest extends AbstractUiTest {

    private static final String USERNAME = "audit-export-form-viewer";
    private static final String PASSWORD = "audit-export-test-password";

    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void seedAGrantedViewer() {
        Organisation careProviderOrg = seededCareProvider();
        Home home = new Home();
        home.setName("Audit Export Form House");
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);

        User user = new User();
        user.setUsername(USERNAME);
        user.setEmail(USERNAME + "@example.test");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setLastName("Audit Export Tester");
        user.setRoles(Set.of(Role.VIEWER));
        user.setHomes(new HashSet<>(Set.of(home)));
        user.setCanExport(true);
        user.setEnabled(true);
        userRepository.save(user);
    }

    @Test
    void pressingExportOnTheRenderedFormProducesAnExportNotA403() {
        login(USERNAME, PASSWORD);
        page.navigate(url("/audit"));
        page.waitForSelector("form[action='/audit/export'] button[type='submit']");

        page.selectOption("#purpose", new SelectOption().setIndex(0));
        page.fill("#reference", "T377 rendered-form check");
        page.click("form[action='/audit/export'] button[type='submit']");
        page.waitForSelector("h1");

        String body = page.locator("body").textContent();
        assertThat(body)
                .as("the submission must not be refused by CsrfFilter")
                .doesNotContain("Error 403");
        assertThat(body)
                .as("and must land on the export-ready page with a download on offer")
                .contains("Case activity export")
                .contains("Download CSV");
    }
}

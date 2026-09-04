package ninja.samryecroft.returnhome.tracker.demo;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Captures the primary user journey as full-page screenshots, for use in client-facing material.
 *
 * <p>This is a capture tool rather than a test: it asserts nothing beyond "the page loaded", and it
 * drives a <em>separately running</em> instance rather than standing one up itself. That is
 * deliberate. The shots have to come from the real application on the real demo seed, and pointing
 * at an already-running instance also sidesteps the port contention that makes the Testcontainers UI
 * tests flaky when several run at once.
 *
 * <p>Bring the app up first ({@code ./scripts/demo-up.sh}), then:
 *
 * <pre>
 * ./mvnw test -Dtest=JourneyCapture -DfailIfNoSpecifiedTests=false \
 *     -Djourney.baseUrl=http://localhost:8080 -Djourney.outputDir=/somewhere/screenshots
 * </pre>
 *
 * <p>It is skipped unless {@code journey.baseUrl} is set, so a normal {@code ./mvnw test} never
 * tries to connect to an app that is not there.
 */
class JourneyCapture {

    private static final String PASSWORD = System.getProperty("journey.password", "demo1234");
    private static final int VIEWPORT_WIDTH = 1440;
    private static final int VIEWPORT_HEIGHT = 900;

    private final List<String> captured = new ArrayList<>();
    private Path outputDir;
    private String baseUrl;
    private int step;

    @Test
    void captureTheJourney() {
        baseUrl = System.getProperty("journey.baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            System.out.println("journey.baseUrl not set - skipping capture.");
            return;
        }
        outputDir = Path.of(System.getProperty("journey.outputDir", "target/journey"));
        outputDir.toFile().mkdirs();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium()
                    .launch(new BrowserType.LaunchOptions().setHeadless(true));
            try {
                capture(browser);
            } finally {
                browser.close();
            }
        }

        System.out.println("\n=== captured " + captured.size() + " screenshots into " + outputDir);
        captured.forEach(name -> System.out.println("  " + name));
    }

    private void capture(Browser browser) {
        // 1-2. Signing in, and what a home is shown on arrival.
        as(browser, "homestaff", page -> {
            shot(page, "login-screen", () -> page.navigate(baseUrl + "/login"));
            signIn(page, "homestaff");
            // "/" redirects a home-staff user straight to their request list, so that IS the dashboard.
            shot(page, "home-staff-dashboard", () -> page.navigate(baseUrl + "/"));
            shot(page, "home-staff-children", () -> page.navigate(baseUrl + "/children"));
            shot(page, "raise-request-form", () -> page.navigate(baseUrl + "/requests/new"));
        });

        // 3. The coordinator picks the request up, allocates a visitor and sets a date.
        as(browser, "coordinator", page -> {
            signIn(page, "coordinator");
            shot(page, "coordinator-queue", () -> page.navigate(baseUrl + "/coordinator/requests"));
            shot(page, "coordinator-allocate",
                    () -> page.navigate(baseUrl + "/coordinator/requests/1/allocate"));
            shot(page, "request-detail", () -> page.navigate(baseUrl + "/interview-requests/6"));
        });

        // 4. The visitor writes and submits the report.
        as(browser, "visitor", page -> {
            signIn(page, "visitor");
            shot(page, "visitor-interviews", () -> page.navigate(baseUrl + "/visitor/interviews"));
            shot(page, "visitor-report-form",
                    () -> page.navigate(baseUrl + "/visitor/interviews/4/report"));
        });

        // 5. The reviewer approves it - and sees it read-only, which is the point of that screen.
        as(browser, "reviewer", page -> {
            signIn(page, "reviewer");
            shot(page, "reviewer-queue", () -> page.navigate(baseUrl + "/reviewer/reports"));
            shot(page, "reviewer-read-only-report",
                    () -> page.navigate(baseUrl + "/reviewer/reports/5/review"));
        });

        // 6. The approved report and its generated Word document.
        as(browser, "homestaff", page -> {
            signIn(page, "homestaff");
            shot(page, "approved-report", () -> page.navigate(baseUrl + "/interview-requests/6"));
        });

        // Administration, for the "how is it set up" part of a pitch.
        as(browser, "admin", page -> {
            signIn(page, "admin");
            shot(page, "admin-users", () -> page.navigate(baseUrl + "/admin/users"));
            shot(page, "admin-organisations", () -> page.navigate(baseUrl + "/admin/organisations"));
            shot(page, "admin-theme", () -> page.navigate(baseUrl + "/admin/theme"));
        });

        // Tenant separation: a different supplier's coordinator sees none of the above.
        as(browser, "coordinator.ng", page -> {
            signIn(page, "coordinator.ng");
            shot(page, "tenant-separation",
                    () -> page.navigate(baseUrl + "/coordinator/requests"));
        });
    }

    private void as(Browser browser, String who, java.util.function.Consumer<Page> work) {
        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT))) {
            Page page = context.newPage();
            work.accept(page);
        }
    }

    private void signIn(Page page, String username) {
        page.navigate(baseUrl + "/login");
        page.fill("#username", username);
        page.fill("#password", PASSWORD);
        page.getByRole(AriaRole.BUTTON).first().click();
        page.waitForLoadState();
    }

    /** Numbered so the filenames themselves carry the order of the story. */
    private void shot(Page page, String name, Runnable navigate) {
        navigate.run();
        page.waitForLoadState();
        String filename = String.format("%02d-%s.png", ++step, name);
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(outputDir.resolve(filename))
                .setFullPage(true));
        captured.add(filename + "  (" + page.url().replace(baseUrl, "") + ")");
    }
}

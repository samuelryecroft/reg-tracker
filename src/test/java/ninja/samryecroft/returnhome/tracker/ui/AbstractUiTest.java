package ninja.samryecroft.returnhome.tracker.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.ArrayList;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// T212: this class carried @Tag("flaky-infra") until 2026-09-08, which quarantined the ENTIRE
// Playwright suite out of the required gate - JUnit 5 inherits a class-level tag to subclasses, so
// tagging this base tagged all thirteen *UiTest classes at once. The whole browser-rendering
// evidence base therefore reported rather than prevented.
//
// WHY IT WAS QUARANTINED, kept because it is the reason it must not be re-added casually: WS-E
// judged the suite infra-timing-flaky (real browser + Hikari pool + Testcontainers Postgres) after
// T21. THAT CAUSE WAS REPAIRED AND THE QUARANTINE STAYED. AbstractIntegrationTest's own javadoc
// records the repairs: one shared container instead of a per-class @Container (T21), one
// @DynamicPropertySource on the base instead of six identical ones opening six contexts and
// exhausting the pool (T148), and identity-based rather than sort-order-based reference lookup
// (T120). Each fix is documented in the very file that went on carrying the tag.
//
// MEASURED BEFORE REMOVING, not assumed: the non-blocking lane passed 37 of 37 completed runs at
// the STEP level - checked at the step rather than the job, because continue-on-error reports a job
// green with a failed step inside it. A quarantine lane that has caught nothing in forty runs is
// not protecting the gate from anything; it is only withholding evidence from it.
//
// Kevin's ruling (T212): a false red is visible and self-correcting, silent non-coverage is
// invisible and self-perpetuating. If these tests do start flaking, the answer is a quarantine that
// carries a named cause and a review date - see QuarantineDisciplineGuardTest, which now enforces
// exactly that - and not a tag that outlives its reason by inertia.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractUiTest extends AbstractIntegrationTest {

    /** There is no default seed password any more, so tests that sign in as the platform admin
     * have to supply one - the same way a real deployment injects it from the environment. */
    protected static final String ADMIN_USERNAME = "admin";
    protected static final String ADMIN_PASSWORD = "ui-test-seed-password";

    @DynamicPropertySource
    static void adminSeedCredentials(DynamicPropertyRegistry registry) {
        registry.add("app.admin.username", () -> ADMIN_USERNAME);
        registry.add("app.admin.password", () -> ADMIN_PASSWORD);
    }

    private static Playwright playwright;
    private static Browser browser;

    @LocalServerPort
    protected int port;

    protected BrowserContext context;
    protected Page page;

    /** Extra contexts opened via {@link #newPageWithJavaScript(boolean)}, closed alongside the
     * default one - a test that opens one of these must not also have to remember to close it. */
    private final List<BrowserContext> extraContexts = new ArrayList<>();

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void closeBrowser() {
        browser.close();
        playwright.close();
    }

    @BeforeEach
    void newPage() {
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void closePage() {
        context.close();
        extraContexts.forEach(BrowserContext::close);
        extraContexts.clear();
    }

    /** A second, independent page - same server, its own cookies/session - with JavaScript on or
     * off. For D-1e-5-shaped requirements ("must work with JS off"): the only way to test the
     * no-JS claim is a browser that genuinely has none, not a page that happens not to trigger any
     * script. Tracked and closed automatically alongside the default page. */
    protected Page newPageWithJavaScript(boolean javaScriptEnabled) {
        BrowserContext extra = browser.newContext(
                new Browser.NewContextOptions().setJavaScriptEnabled(javaScriptEnabled));
        extraContexts.add(extra);
        return extra.newPage();
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected void login(String username, String password) {
        login(page, username, password);
    }

    /** Same steps, on a page other than the default one - the only shape
     * {@link #newPageWithJavaScript} is useful for signing in on. */
    protected void login(Page targetPage, String username, String password) {
        targetPage.navigate(url("/login"));
        targetPage.fill("#username", username);
        targetPage.fill("#password", password);
        targetPage.click("button[type=submit]");
        targetPage.waitForLoadState();
    }
}

package ninja.samryecroft.returnhome.tracker.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected void login(String username, String password) {
        page.navigate(url("/login"));
        page.fill("#username", username);
        page.fill("#password", password);
        page.click("button[type=submit]");
        page.waitForLoadState();
    }
}

package ninja.samryecroft.returnhome.tracker.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ninja.samryecroft.returnhome.tracker.user.password.PasswordContext;
import ninja.samryecroft.returnhome.tracker.user.password.PasswordPolicy;
import org.junit.jupiter.api.Test;

/**
 * T312: the demo's shipped credentials must be credentials this product would actually accept.
 *
 * <h2>What went wrong, because it explains the shape of this test</h2>
 *
 * <p>The demo password was {@code demo1234} - eight characters, against a policy whose minimum is
 * twelve. <b>The policy tightened and the seed did not move with it</b>, and nothing failed: no test
 * covered the shipped default, so the first thing that noticed was a person signing in.
 *
 * <p><b>And the failure was PARTIAL, which is why it survived.</b> {@code AdminUserSeeder} checks
 * the policy (T272 R5) and so refused to create the admin at all; {@code DemoDataSeeder} does not
 * check, and created the other nine accounts happily. So a demo signed in fine as {@code
 * coordinator} and could not get into {@code admin} - following DEMO.md, which said the password
 * worked. A total failure would have been found in minutes.
 *
 * <h2>Why it reads the FILES rather than the running configuration</h2>
 *
 * <p>A {@code @SpringBootTest} on the demo profile would test whatever the test environment injects,
 * which is the one configuration that is never the problem. <b>The thing that ships is the default
 * inside {@code ${DEMO_PASSWORD:...}}</b>, and that is what a presenter gets when they run
 * {@code demo-up.sh} with no environment set - so that literal is what is checked, the same way
 * {@code FrontendSourceGuardTest} looks at source files for a class of defect no running test can
 * see.
 *
 * <p><b>This is a test rather than a comment on the properties file for the reason T321 settled:</b>
 * a note saying "keep this above twelve characters" stays on the page, unchanged and unread, the
 * next time the minimum moves. This goes red instead.
 */
class TheDemoCredentialsStillSatisfyThePasswordPolicyTest {

    private static final Path DEMO_PROPERTIES = Path.of("src/main/resources/application-demo.properties");
    private static final Path DEMO_PROPERTIES_CLASS =
            Path.of("src/main/java/ninja/samryecroft/returnhome/tracker/demo/DemoProperties.java");
    private static final Path DEMO_DOC = Path.of("DEMO.md");

    /** The application name is a significant value in the policy, so the real one has to be used. */
    private static final PasswordPolicy POLICY = new PasswordPolicy("return-home-tracker");

    /**
     * Every username {@code DemoDataSeeder} creates. They all share one password, so the shared
     * value must be acceptable for ALL of them - a password containing any one username is refused
     * for that account and would break exactly one login, which is this card's failure mode again.
     */
    private static final List<String> DEMO_USERNAMES = List.of("admin", "orgadmin", "coordinator",
            "visitor", "visitor2", "reviewer", "homestaff", "homestaff2", "viewer", "coordinator.ng");

    @Test
    void theSeededAdminPasswordWouldBeAcceptedByTheSeeder() throws IOException {
        String shipped = defaultOf("app.admin.password");

        assertThat(POLICY.rejectionFor(shipped, new PasswordContext("admin", null, null)))
                .as("AdminUserSeeder refuses a password the policy rejects and creates NO admin, so "
                        + "this failing means nobody can sign in to the demo at all")
                .isEmpty();
    }

    /**
     * And the shared demo password, checked against every account that uses it.
     *
     * <p>{@code DemoDataSeeder} does NOT check the policy at runtime, which is precisely why this
     * has to be checked here: there is no other point at which a bad value is refused, so without
     * this the only detector is a person failing to sign in during a demo.
     */
    @Test
    void theSharedDemoPasswordWouldBeAcceptedForEveryAccountThatUsesIt() throws IOException {
        String shipped = defaultOf("app.demo.password");

        for (String username : DEMO_USERNAMES) {
            assertThat(POLICY.rejectionFor(shipped, new PasswordContext(username, null, null)))
                    .as("the demo password has to be acceptable for %s too - one shared value, ten "
                            + "accounts, and a rejection would break exactly one login", username)
                    .isEmpty();
        }
    }

    /**
     * THE THREE COPIES MUST AGREE, and this is the half that catches a partial fix.
     *
     * <p>The value lives in the properties file, in {@link DemoProperties}'s Java default, and in
     * DEMO.md. Someone lengthening the password to satisfy the assertions above could easily update
     * one or two - and the failure would be a presenter typing what the documentation told them and
     * being refused, which is this card's defect returning by a different door.
     */
    @Test
    void thePropertiesFileTheJavaDefaultAndTheDocumentationCarryTheSameValue() throws IOException {
        String shipped = defaultOf("app.demo.password");

        assertThat(defaultOf("app.admin.password"))
                .as("the demo seeds one password for everybody including the admin; two values here "
                        + "would mean DEMO.md is wrong for one of them")
                .isEqualTo(shipped);
        assertThat(Files.readString(DEMO_PROPERTIES_CLASS, StandardCharsets.UTF_8))
                .as("DemoProperties' Java default is the value used if the properties file is ever "
                        + "not on the classpath - two spellings of one value is the shape that drifts")
                .contains("private String password = \"" + shipped + "\";");
        assertThat(Files.readString(DEMO_DOC, StandardCharsets.UTF_8))
                .as("DEMO.md is what a presenter reads before typing")
                .contains("`" + shipped + "`");
    }

    /**
     * The shipped default from {@code ${KEY:default}}, which is what a presenter gets with no
     * environment set - not whatever this JVM happens to have.
     */
    private static String defaultOf(String key) throws IOException {
        String properties = Files.readString(DEMO_PROPERTIES, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("^" + Pattern.quote(key) + "=\\$\\{[A-Z_]+:([^}]*)}$",
                Pattern.MULTILINE).matcher(properties);
        assertThat(matcher.find())
                .as("%s should be declared as ${ENV:default} in %s - if that shape has changed, this "
                        + "test is reading the wrong thing and must be updated rather than deleted",
                        key, DEMO_PROPERTIES)
                .isTrue();
        return matcher.group(1);
    }
}

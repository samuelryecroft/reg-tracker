package ninja.samryecroft.returnhome.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import ninja.samryecroft.returnhome.tracker.ui.AbstractUiTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A fence around T148: declaring {@code @DynamicPropertySource} costs a whole Spring test context,
 * so only the two base classes may do it. Named for the mechanism it fences rather than for the
 * outcome, because it does not on its own guarantee that the integration tests share one context -
 * see the scope note below.
 *
 * <p>Spring keys its test-context cache on {@code DynamicPropertiesContextCustomizer}, whose
 * {@code equals} compares the <em>set of registrar methods</em> and never looks at what those
 * methods register. Two classes registering byte-identical values are therefore still two cache
 * keys, two contexts and two Hikari pools against the one shared Postgres container. Six such
 * classes plus the rest of the suite exhausted the container's hundred connections, and the
 * eleventh context died on "FATAL: sorry, too many clients already" - in whichever class happened
 * to run eleventh, which is why it was twice written off as environmental flakiness (T93/T120)
 * before it was measured (T148).
 *
 * <p>That makes the cost invisible at the point where it is paid: adding a registrar to one class
 * is three innocuous-looking lines, and the suite stays green until it abruptly does not. This test
 * is the missing feedback. It is not a prohibition - a test that genuinely needs its own context
 * may still have one - but it has to be a deliberate, reviewed decision recorded in
 * {@link #ALLOWED} rather than something that arrives unnoticed in an unrelated PR.
 *
 * <p>It reads compiled classes rather than source text on purpose: {@code WebkitIconSmokeUiTest}
 * discusses {@code @DynamicPropertySource} at length in its javadoc while carefully not declaring
 * one, so a grep over {@code src/test/java} reports it and is wrong. Reflection sees what the JVM
 * sees.
 *
 * <p>The check runs in both directions, because {@code containsExactlyInAnyOrder} does: it fails if
 * a seventh class gains a registrar, and equally if {@link AbstractIntegrationTest} ever loses its
 * own - which would silently unshare the document store and put the suite back where T148 started,
 * with everything still green.
 *
 * <p><strong>Scope, so this is not read as more than it is.</strong> It fences one fragmentation
 * mechanism - registrar methods - and not context fragmentation in general. A {@code @MockBean}, a
 * different profile, a different {@code @SpringBootTest} webEnvironment or an added
 * {@code @TestPropertySource} each fork a context too, and none of them trip this test. T148 ended
 * at six contexts rather than one for exactly those reasons, which at Hikari's default pool size is
 * fifty connections against a ceiling of a hundred: real headroom, but the same failure returns if
 * the count creeps back up. What the other five are is a separate question this test does not
 * answer.
 *
 * <p>Needs no application context of its own, which is rather the point.
 */
class DynamicPropertySourceGuardTest {

    /**
     * The classes allowed to declare a registrar, and why each is worth a context.
     *
     * <ul>
     *   <li>{@link AbstractIntegrationTest} - the one every integration test inherits, so it
     *       fragments nothing: it registers the shared document store.
     *   <li>{@link AbstractUiTest} - the Playwright suite needs an admin seed password
     *       ({@code app.admin.username}/{@code app.admin.password}) that must not be set for
     *       everyone else, and those tests already run in a separate context because they need a
     *       real servlet container on a random port.
     * </ul>
     */
    private static final List<Class<?>> ALLOWED = List.of(AbstractIntegrationTest.class, AbstractUiTest.class);

    @Test
    void onlyTheSharedBaseClassesDeclareADynamicPropertySource() {
        List<Class<?>> compiledTestClasses = compiledTestClasses();

        // A scan that silently found nothing would make the assertion below vacuously reassuring.
        // A floor rather than a count: it is here to catch a broken walk, not to police suite size.
        assertThat(compiledTestClasses)
                .as("the scan found the compiled test classes")
                .hasSizeGreaterThan(25);

        assertThat(compiledTestClasses.stream().filter(DynamicPropertySourceGuardTest::declaresRegistrar).toList())
                .as("""
                        Every class declaring @DynamicPropertySource gets its own Spring test context \
                        and its own Hikari pool against the shared Postgres container, whatever it \
                        registers - Spring compares the registrar METHODS, not their values. Enough of \
                        them and a later context fails to start with "FATAL: sorry, too many clients \
                        already", in whichever class happens to run last (T148).

                        If this test is failing, the new registrar almost certainly belongs on \
                        AbstractIntegrationTest, where every integration test already shares it. If the \
                        class genuinely needs its own context, add it to ALLOWED with a comment saying \
                        why the extra context is worth paying for.""")
                .containsExactlyInAnyOrderElementsOf(ALLOWED);
    }

    private static boolean declaresRegistrar(Class<?> type) {
        return Stream.of(type.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(DynamicPropertySource.class));
    }

    /**
     * Every compiled test class, loaded without running static initialisers - {@link
     * AbstractIntegrationTest} starts a Postgres container in one of those.
     */
    private static List<Class<?>> compiledTestClasses() {
        Path root = testClassesDirectory();
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(path -> path.toString().endsWith(".class"))
                    .map(path -> root.relativize(path).toString()
                            .replace(java.io.File.separatorChar, '.')
                            .replaceFirst("\\.class$", ""))
                    .map(DynamicPropertySourceGuardTest::load)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + root, e);
        }
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className, false, DynamicPropertySourceGuardTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Compiled test class " + className + " is not loadable", e);
        }
    }

    /** Located from this class's own code source, so it does not depend on the working directory. */
    private static Path testClassesDirectory() {
        try {
            return Path.of(DynamicPropertySourceGuardTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Could not locate the compiled test classes", e);
        }
    }
}

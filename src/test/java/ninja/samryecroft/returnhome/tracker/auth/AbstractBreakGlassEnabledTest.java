package ninja.samryecroft.returnhome.tracker.auth;

import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;

/**
 * One context for every test that needs the emergency sign-in path open.
 *
 * <p><b>This is the sixth Hikari pool, spent deliberately.</b> The gate is startup-bound, so a test
 * that needs it on must override the property, and that forks a context - see
 * {@code TEST-CONTEXTS.md}, where 5 was the baseline and 7 is the ticket threshold. A per-request
 * read would have avoided it, and was rejected: <b>a test-infrastructure budget must not shape a
 * security control's runtime semantics.</b> The budget exists to stop us arriving at seven by
 * accident, not to stop us spending one on purpose.
 *
 * <p>Everything that needs the path open lives behind this class so that however many such tests
 * there end up being, they cost one pool between them.
 *
 * <p><b>The Entra flag is left at its default of false</b>, and that is the point rather than an
 * omission. Break-glass exists for the case where single sign-on is off or broken - §5's rollback is
 * literally "disable Entra, go back to form login" - so this is the honest configuration for the
 * emergency path. It is also a structural proof: a WARN line or audit event accidentally gated
 * behind {@code if (entraEnabled)}, which is where someone tidying Entra code would put it, cannot
 * pass a test that runs with Entra off.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.auth.break-glass.enabled=true")
public abstract class AbstractBreakGlassEnabledTest extends AbstractIntegrationTest {
}

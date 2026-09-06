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
 * <p>This used to note that the Entra flag was left at its default of false, and that running with
 * it off was a structural proof that no WARN line or audit event had been gated behind
 * {@code if (entraEnabled)}. <b>Entra has been removed, so there is no flag and that proof no
 * longer proves anything</b> - recorded rather than deleted, because the property it protected is
 * still the one that matters: break-glass exists for when the ordinary way in does not work, so
 * nothing about it may be conditional on the ordinary way in being available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.auth.break-glass.enabled=true")
public abstract class AbstractBreakGlassEnabledTest extends AbstractIntegrationTest {
}

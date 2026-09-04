package ninja.samryecroft.returnhome.tracker.auth;

import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * One context for every test that needs Entra sign-in switched on.
 *
 * <p><b>This exists to keep the suite inside the budget in {@code TEST-CONTEXTS.md}</b>, which
 * records 5 Hikari pools against 97 usable connections, with 7 as "open a ticket" and 9 as "stop".
 * Adding the logout tests took it to 6 - measured, by the highest {@code HikariPool-N} in a full run
 * - and the break-glass tests would have taken it to 7, arriving at the ticket threshold without
 * anyone choosing it. That is how T148 happened.
 *
 * <p><b>Sharing works here for a reason that is the mirror of T148's, not an exception to it.</b>
 * {@code @DynamicPropertySource} forks per registrar <em>method</em> even when the values are
 * identical, which is what made eight identical registrars into eight contexts.
 * {@code @TestPropertySource} keys on the merged property <em>values</em>, so classes carrying an
 * identical set genuinely do share. Putting the properties and the stub registration here, rather
 * than repeating a near-identical set per class, is what makes the sharing real: a subclass that
 * adds its own {@code @TestPropertySource} would fork a context again.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.auth.entra.enabled=true")
@Import(EntraStubTenantConfig.class)
public abstract class AbstractEntraEnabledTest extends AbstractIntegrationTest {
}

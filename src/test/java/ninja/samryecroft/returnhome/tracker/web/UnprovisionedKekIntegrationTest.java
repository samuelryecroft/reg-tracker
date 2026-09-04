package ninja.samryecroft.returnhome.tracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.document.KeyHandle;
import ninja.samryecroft.returnhome.tracker.document.KeyProvider;
import ninja.samryecroft.returnhome.tracker.document.KeyUnavailableException;
import ninja.samryecroft.returnhome.tracker.document.WrappedKey;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.FieldCryptoException;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T168, the org-2 condition driven end to end: an organisation whose per-organisation KEK was never
 * provisioned. Both halves of the response to that live here - the admin being WARNED at onboarding,
 * and the write later FAILING WELL if nobody acted on the warning.
 *
 * <p>They share one class because they need the same two things - a key provider that fails every
 * operation, and auto-create off - and a class each would have cost a Spring context and a
 * connection pool to say the same thing twice (TEST-CONTEXTS.md).
 *
 * <p>The routing half exists because the part that broke was ROUTING.
 *
 * <p>{@code GlobalControllerAdviceFieldCryptoTest} calls {@link GlobalControllerAdvice#handleFieldCrypto}
 * directly and proves the MAPPING. That leaves the question the incident actually turned on
 * untested: does a {@link FieldCryptoException} raised on a real add-child ever reach that handler?
 * It is not obvious that it does. Field encryption runs in a Hibernate {@code PreInsertEventListener},
 * so the throw happens inside flush rather than in the controller body, and it has to survive
 * Hibernate, the transaction interceptor and Spring's handler resolution to arrive anywhere useful.
 *
 * <p>Writing this test is what showed the ticket's premise to be wrong. Before the handler existed
 * this request did NOT produce the assumed default 500: Spring's {@code @ExceptionHandler}
 * resolution walks the CAUSE CHAIN, {@link KeyUnavailableException} extends
 * {@code DocumentSecurityException}, and {@code OrgFieldKeyStore} rewraps it preserving the cause -
 * so {@code handleDocumentSecurity} matched and returned 503 already. Measured, both ways, with the
 * annotation removed and restored.
 *
 * <p>That is why this test asserts the NOUN and not just the status. The status was never the
 * defect; the defect was that someone adding a CHILD was told a REPORT could not be opened. A test
 * that checked only for 503 would have passed against the broken build - which is precisely the
 * mistake that let the premise stand unexamined.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.documents.key-vault.auto-create-keys=false")
class UnprovisionedKekIntegrationTest extends AbstractIntegrationTest {

    /**
     * Stands in for the org-2 condition: the organisation's KEK was never provisioned, and the
     * application runs as Key Vault Crypto User so it cannot create one. Every operation fails the
     * way {@code KeyVaultKeyProvider} fails it - {@link KeyUnavailableException}, which is what
     * {@code OrgFieldKeyStore.create} catches and rewraps as a {@link FieldCryptoException}.
     */
    @TestConfiguration
    static class UnprovisionedKek {

        @Bean
        @Primary
        KeyProvider unprovisionedKeyProvider() {
            return new KeyProvider() {

                @Override
                public KeyHandle currentKeyFor(long organisationId) {
                    throw new KeyUnavailableException("No key exists for organisation " + organisationId
                            + " and key creation is disabled; provision "
                            + KeyProvider.keyNameFor(organisationId) + " before its first record");
                }

                @Override
                public WrappedKey wrap(KeyHandle handle, byte[] dataKey) {
                    throw new KeyUnavailableException("No usable key for " + handle.keyName());
                }

                @Override
                public byte[] unwrap(long organisationId, WrappedKey wrappedKey) {
                    throw new KeyUnavailableException("No usable key for organisation " + organisationId);
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private OrganisationRepository organisationRepository;

    private Home home;
    private String suffix;

    @BeforeEach
    void seedAnOrganisationWhoseKeyWasNeverProvisioned() {
        suffix = "-" + System.nanoTime();
        Organisation careProvider = seededCareProvider();

        home = new Home();
        home.setName("Unprovisioned House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        User admin = new User();
        admin.setUsername("kek-admin" + suffix);
        admin.setPassword(passwordEncoder.encode("password123"));
        admin.setFirstName("Kek");
        admin.setLastName("Admin");
        admin.setEmail("kek" + suffix + "@example.test");
        admin.setOrganisation(careProvider);
        admin.setRoles(new HashSet<>(Set.of(Role.ORG_ADMIN)));
        admin.setHomes(new HashSet<>(Set.of(home)));
        userRepository.save(admin);

        User platformAdmin = new User();
        platformAdmin.setUsername("kek-platform-admin" + suffix);
        platformAdmin.setPassword(passwordEncoder.encode("password123"));
        platformAdmin.setFirstName("Platform");
        platformAdmin.setLastName("Admin");
        platformAdmin.setEmail("kek-pa" + suffix + "@example.test");
        platformAdmin.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        platformAdmin.setHomes(new HashSet<>());
        userRepository.save(platformAdmin);
    }

    @Test
    void addingAChildWithNoProvisionedKekFailsClosedAsAnActionable503AboutTheRecord() throws Exception {
        long childrenBefore = childRepository.count();

        MvcResult result = mockMvc.perform(post("/children")
                        .with(asUser("kek-admin" + suffix)).with(csrf())
                        .param("firstName", "Unsaved")
                        .param("lastName", "Child")
                        .param("dateOfBirth", LocalDate.of(2012, 1, 1).toString())
                        .param("homeId", home.getId().toString()))
                .andReturn();

        // Transient and operational: the remedy is provisioning the key, not a code change.
        assertThat(result.getResponse().getStatus()).isEqualTo(503);

        // The exception really did travel out of the Hibernate flush as a FieldCryptoException and
        // get resolved - not swallowed, and not left to the container.
        assertThat(result.getResolvedException()).isInstanceOf(FieldCryptoException.class);

        String message = String.valueOf(result.getModelAndView().getModel().get("message"));

        // The load-bearing assertion. handleDocumentSecurity ALSO answers 503 here, via the cause
        // chain, so status alone cannot tell the two handlers apart - and its message says a REPORT
        // cannot be OPENED, to someone who was adding a child. That noun is the defect.
        assertThat(message).doesNotContain("report");
        assertThat(message).contains("record");

        // Fail-closed, and it is worth asserting rather than assuming: there is no variant of this
        // that keeps the child without its encrypted fields.
        assertThat(childRepository.count()).isEqualTo(childrenBefore);
    }

    /**
     * T168 Part A(a): the onboarding notice actually REACHES THE PAGE.
     *
     * <p>The controller test proves the flash attribute is set. It cannot prove an admin ever sees
     * it, and in this codebase that gap is not theoretical: T165 found the shared {@code fieldError}
     * fragment sitting in {@code <head>}, where the parser's auto-close pushed its {@code <p>} out of
     * its own {@code th:fragment} block. It had never rendered - no inline validation message on any
     * form, every input's {@code aria-describedby} dangling at an id that was never emitted - and the
     * whole suite stayed green throughout, because every test asserted the model and none asserted
     * the page. "The markup looks right" is not evidence here.
     *
     * <p>MockMvc does not carry a flash attribute across a redirect by itself, so the notice is taken
     * from the POST's own flash map and replayed on the GET. That still proves the two things that
     * can independently break: the create path SETS it, and the organisation-list template RENDERS
     * it. The key name is asserted because it is the whole point of the notice - an admin who cannot
     * see which key to provision has been told only that something is wrong.
     */
    @Test
    void theOnboardingNoticeNamesTheKeyAndActuallyReachesTheOrganisationsPage() throws Exception {
        MvcResult created = mockMvc.perform(post("/admin/organisations")
                        .with(asUser("kek-platform-admin" + suffix)).with(csrf())
                        .param("name", "Unprovisioned Care" + suffix)
                        .param("type", "CARE_PROVIDER")
                        .param("supplierOrganisationId", seededSupplier().getId().toString()))
                .andReturn();

        Object notice = created.getFlashMap().get("kekWarning");
        assertThat(notice).as("creating a CARE_PROVIDER whose KEK cannot be confirmed must raise the "
                + "onboarding notice - auto-create is off for this class, so the probe is not skipped")
                .isNotNull();

        String html = mockMvc.perform(get("/admin/organisations")
                        .with(asUser("kek-platform-admin" + suffix))
                        .flashAttr("kekWarning", notice))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(String.valueOf(notice));
        assertThat(html).contains("Encryption key could not be confirmed");

        // The actionable half: an admin needs the key's NAME to provision it. Withholding it here
        // would leave the notice saying only that something is wrong - and this is the privileged
        // admin screen, which is exactly why it is named here and withheld from the end-user 503.
        Organisation created0 = organisationRepository.findAll().stream()
                .filter(o -> ("Unprovisioned Care" + suffix).equals(o.getName()))
                .findFirst().orElseThrow();
        assertThat(html).contains(KeyProvider.keyNameFor(created0.getId()));

        // The marker is decoration: the sentence carries the meaning, so the icon must be hidden.
        assertThat(html).contains("#ph-warning-circle");
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
        return securityContext(context);
    }
}

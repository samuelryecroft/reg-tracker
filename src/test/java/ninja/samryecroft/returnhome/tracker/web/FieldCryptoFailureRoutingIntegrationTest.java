package ninja.samryecroft.returnhome.tracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T168: the org-2 failure driven end to end, because the half that broke was ROUTING.
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
class FieldCryptoFailureRoutingIntegrationTest extends AbstractIntegrationTest {

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

    private RequestPostProcessor asUser(String username) {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
        return securityContext(context);
    }
}

package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T113 Inc 2: an ORG_ADMIN records the person's Entra directory object id when the account is
 * created, so sign-in is a plain lookup and there is no first-login matching ceremony.
 *
 * <p>This form field is the single point in the whole system where a human transcribes an
 * identifier, and every way of getting it wrong fails <em>somewhere else</em>: not here, but at that
 * person's first sign-in, behind a refusal message that deliberately says nothing useful. So the
 * cases below are about catching those at the keyboard - a malformed paste, a duplicate, and the
 * casing difference that would otherwise produce an account that silently never matches.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DirectoryObjectIdFormIntegrationTest extends AbstractIntegrationTest {

    private static final String OBJECT_ID = "6f0a1c9e-3c2b-4c1a-9f77-0c0a1b2c3d4e";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private String suffix;
    private Organisation supplier;

    @BeforeEach
    void seedAdmin() {
        suffix = "-" + System.nanoTime();
        supplier = seededSupplier();
        User admin = new User();
        admin.setUsername("objectid-admin" + suffix);
        admin.setFirstName("Object");
        admin.setLastName("Admin");
        admin.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        admin.setEnabled(true);
        userRepository.save(admin);
    }

    @Test
    void theObjectIdIsRecordedAtAccountCreationSoSignInIsAPlainLookup() throws Exception {
        create("linked" + suffix, OBJECT_ID).andExpect(status().is3xxRedirection());

        User saved = userRepository.findByUsername("linked" + suffix).orElseThrow();
        assertThat(saved.getIdpSubject()).isEqualTo(OBJECT_ID);
        // The property that matters: the sign-in finder resolves it. Asserting the column alone
        // would not show that the value an admin typed is the value a login looks up.
        assertThat(userRepository.findByIdpSubject(OBJECT_ID)).get()
                .extracting(User::getId).isEqualTo(saved.getId());
    }

    /**
     * An account may exist without one - the break-glass admin has no directory identity at all
     * (D5), and an account can legitimately be created before anyone has looked the id up. Blank
     * must therefore mean "not recorded", not an empty string that would collide with the next blank
     * one under the unique constraint.
     */
    @Test
    void anAccountWithNoObjectIdIsAllowedAndStoresNullRatherThanBlank() throws Exception {
        create("unlinked" + suffix, "").andExpect(status().is3xxRedirection());

        assertThat(userRepository.findByUsername("unlinked" + suffix).orElseThrow().getIdpSubject()).isNull();
    }

    /**
     * The portal renders the id in lower case and the sign-in lookup is an exact string match, so an
     * upper-cased paste would create an account that never matches - failing at that person's first
     * sign-in, not here. Normalising removes the failure rather than reporting it.
     */
    @Test
    void anUpperCasedPasteIsNormalisedRatherThanStoredAsAnIdThatWillNeverMatch() throws Exception {
        create("shouty" + suffix, OBJECT_ID.toUpperCase()).andExpect(status().is3xxRedirection());

        User saved = userRepository.findByUsername("shouty" + suffix).orElseThrow();
        assertThat(saved.getIdpSubject()).isEqualTo(OBJECT_ID);
        assertThat(userRepository.findByIdpSubject(OBJECT_ID)).get()
                .extracting(User::getId).isEqualTo(saved.getId());
    }

    @Test
    void aMalformedObjectIdIsAFieldErrorAndNothingIsPersisted() throws Exception {
        create("typo" + suffix, "6f0a1c9e-3c2b-4c1a-9f77")
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "idpSubject"));

        assertThat(userRepository.findByUsername("typo" + suffix)).isEmpty();
    }

    /**
     * The case Kevin asked to see explicitly: {@code uq_users_idp_subject} makes this reachable
     * straight from the form, and untranslated it arrives as a 500 that loses everything else the
     * administrator typed. It also means something specific - two application accounts pointing at
     * one directory identity, so whichever signed in would be arbitrary.
     *
     * <p><b>What this test does not pin, measured rather than assumed:</b> removing the service's
     * pre-check leaves it passing. {@code uq_users_idp_subject} still refuses the insert and the
     * controller still translates it, so from the form the two layers are indistinguishable. The
     * pre-check earns its place for the reason a constraint violation cannot be relied on as the
     * ordinary path - it surfaces at flush, once other writes in the same transaction have already
     * happened - but this test is evidence for the outcome, not for which layer produced it.
     */
    @Test
    void aDuplicateObjectIdIsAFieldErrorRatherThanAFiveHundred() throws Exception {
        create("first" + suffix, OBJECT_ID).andExpect(status().is3xxRedirection());

        create("second" + suffix, OBJECT_ID)
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "idpSubject"));

        assertThat(userRepository.findByUsername("second" + suffix)).isEmpty();
        assertThat(userRepository.findByIdpSubject(OBJECT_ID).orElseThrow().getUsername())
                .isEqualTo("first" + suffix);
    }

    /**
     * Editing the same account must not collide with itself. The pre-check compares owners, so
     * re-saving a user whose id is unchanged is not a duplicate - without that, an admin could never
     * edit a linked account's phone number again.
     */
    @Test
    void resavingAnAccountWithItsOwnObjectIdIsNotADuplicate() throws Exception {
        create("editable" + suffix, OBJECT_ID).andExpect(status().is3xxRedirection());
        Long id = userRepository.findByUsername("editable" + suffix).orElseThrow().getId();

        mockMvc.perform(post("/admin/users/{id}/edit", id).with(admin()).with(csrf())
                        .param("firstName", "Renamed")
                        .param("lastName", "Person")
                        .param("email", "renamed@example.test")
                        .param("idpSubject", OBJECT_ID)
                        .param("roles", Role.COORDINATOR.name())
                        .param("organisationId", supplier.getId().toString())
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection());

        User saved = userRepository.findById(id).orElseThrow();
        assertThat(saved.getFirstName()).isEqualTo("Renamed");
        assertThat(saved.getIdpSubject()).isEqualTo(OBJECT_ID);
    }

    private org.springframework.test.web.servlet.ResultActions create(String username, String objectId)
            throws Exception {
        return mockMvc.perform(post("/admin/users").with(admin()).with(csrf())
                .param("username", username)
                .param("password", "a-long-enough-password")
                .param("firstName", "Nadia")
                .param("lastName", "Khan")
                .param("email", username + "@example.test")
                .param("idpSubject", objectId)
                .param("roles", Role.COORDINATOR.name())
                .param("organisationId", supplier.getId().toString()));
    }

    private RequestPostProcessor admin() {
        UserDetails details = appUserDetailsService.loadUserByUsername("objectid-admin" + suffix);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        return securityContext(context);
    }
}

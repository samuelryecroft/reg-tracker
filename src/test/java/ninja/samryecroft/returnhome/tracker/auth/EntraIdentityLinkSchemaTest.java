package ninja.samryecroft.returnhome.tracker.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.UserService;
import ninja.samryecroft.returnhome.tracker.user.dto.CreateUserForm;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * P1 of the Entra work (ENTRA-AUTH-DESIGN.md §6): the schema that makes linking possible, and the
 * two things it must not break while form login is still the live path.
 *
 * <p>Nothing here exercises Entra itself - there is no tenant, and the link is P4. What it does
 * check is that the column exists with the constraint the link rule depends on, and that making
 * credentials optional did not quietly make them optional to <em>authenticate</em> with.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EntraIdentityLinkSchemaTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserService userService;
    @Autowired
    private OrganisationRepository organisationRepository;

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setLastName(username);
        user.setRoles(Set.of(Role.COORDINATOR));
        user.setEnabled(true);
        return user;
    }

    @Test
    void anAccountCanExistWithNoLocalCredential() {
        // The build-breaker P1 exists to clear: users.password was NOT NULL, so the Add-User screen
        // that stops collecting a credential could not insert a row at all.
        User saved = userRepository.saveAndFlush(user("entra-only"));

        assertThat(saved.getPassword()).isNull();
        assertThat(saved.getIdpSubject()).isNull();
    }

    @Test
    void aCredentiallessAccountCannotSignInWithAnEmptyPassword() {
        // The failure mode worth guarding: had the credential-less path stored the encoding of an
        // empty string rather than null, this would be a real, matchable credential and anyone
        // submitting a blank password would authenticate as this account.
        userRepository.saveAndFlush(user("no-credential"));

        assertThatNoLoginSucceeds("no-credential", "");
        assertThatNoLoginSucceeds("no-credential", "anything-at-all");
    }

    @Test
    void anExistingPasswordStillSignsIn() {
        // The other half of "no behaviour change": form login is the live path until cutover, and
        // break-glass keeps a local credential even after it.
        User existing = user("still-local");
        existing.setPassword(passwordEncoder.encode("a-real-password"));
        userRepository.saveAndFlush(existing);

        assertThatLoginSucceeds("still-local", "a-real-password");
    }

    @Test
    void idpSubjectIsUniqueWhenPresentButManyAccountsMayHaveNone() {
        // Both halves matter. Unique stops two application accounts binding to one Entra identity;
        // many-nulls is what lets every account exist unlinked, which is the normal state until
        // each person's first Entra sign-in.
        userRepository.saveAndFlush(user("unlinked-one"));
        userRepository.saveAndFlush(user("unlinked-two"));

        User linked = user("linked");
        linked.setIdpSubject("sub-00000000-0000-0000-0000-000000000001");
        userRepository.saveAndFlush(linked);

        User duplicate = user("duplicate-link");
        duplicate.setIdpSubject("sub-00000000-0000-0000-0000-000000000001");

        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void creatingAUserWithNoPasswordStoresNoCredentialRatherThanABlankOne() {
        // The path Kevin's build-breaker is really about: the Add-User screen stops collecting a
        // credential, so create() receives nothing. Encoding that "nothing" would produce a valid
        // bcrypt hash of the empty string - a credential anyone could present.
        CreateUserForm form = new CreateUserForm();
        form.setUsername("created-without-password");
        form.setFirstName("Created");
        form.setLastName("Without Password");
        form.setEmail("created.without.password@example.test");
        form.setRoles(Set.of(Role.COORDINATOR));
        form.setOrganisationId(organisationRepository.findByTypeOrderByName(OrgType.SUPPLIER).get(0).getId());
        // Exactly what an untouched HTML password field submits.
        form.setPassword("");

        User created = userService.create(form, adminPrincipal());

        assertThat(created.getPassword()).isNull();
        assertThatNoLoginSucceeds("created-without-password", "");
    }

    @Test
    void aSuppliedPasswordIsStillEncodedAndStillUsable() {
        CreateUserForm form = new CreateUserForm();
        form.setUsername("created-with-password");
        form.setFirstName("Created");
        form.setLastName("With Password");
        form.setEmail("created.with.password@example.test");
        form.setRoles(Set.of(Role.COORDINATOR));
        form.setOrganisationId(organisationRepository.findByTypeOrderByName(OrgType.SUPPLIER).get(0).getId());
        form.setPassword("a-real-password");

        User created = userService.create(form, adminPrincipal());

        assertThat(created.getPassword()).isNotNull().isNotEqualTo("a-real-password");
        assertThatLoginSucceeds("created-with-password", "a-real-password");
    }

    private AppUserPrincipal adminPrincipal() {
        User admin = user("p1-admin");
        admin.setRoles(Set.of(Role.ADMIN));
        return new AppUserPrincipal(userRepository.saveAndFlush(admin), false);
    }

    private void assertThatNoLoginSucceeds(String username, String password) {
        try {
            mockMvc.perform(post("/login").with(csrf())
                            .param("username", username)
                            .param("password", password))
                    .andExpect(redirectedUrl("/login?error"));
        } catch (Exception e) {
            throw new AssertionError("login attempt for " + username + " did not fail cleanly", e);
        }
    }

    private void assertThatLoginSucceeds(String username, String password) {
        try {
            mockMvc.perform(post("/login").with(csrf())
                            .param("username", username)
                            .param("password", password))
                    .andExpect(redirectedUrl("/"));
        } catch (Exception e) {
            throw new AssertionError("login attempt for " + username + " did not succeed", e);
        }
    }
}

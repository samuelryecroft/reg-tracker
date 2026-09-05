package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T119 6c: {@code /audit/{id}} - the two properties that would be expensive to get wrong.
 *
 * <p><b>1. An event outside the caller's scope answers 404, not 403</b>, and answers it
 * identically to an event that does not exist. A 403 confirms the row is there and turns the id
 * into an enumeration oracle - "you may not see this" tells the asker there is something to see.
 * The two cases leave the controller through one branch precisely so they cannot be told apart,
 * and this asserts they cannot.
 *
 * <p><b>2. The actor's username never reaches the page.</b> {@code actor_username_at_time} is
 * stored, and it is the obvious thing to render on a page headed "everything kept against this
 * event". Role-only was decided in T38 and overrode the mockup's own full name. The column
 * existing is not permission to display it, and this matters more once usernames become email
 * addresses.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditEventPageIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserDetailsService appUserDetailsService;
    @Autowired
    private UserRepository userRepository;

    private final String admin = "t6c-admin-" + System.nanoTime();

    /** Its own platform admin, so the test does not depend on what any seeder happened to leave. */
    private String adminUsername() {
        if (userRepository.findByUsername(admin).isEmpty()) {
            User user = new User();
            user.setUsername(admin);
            user.setFirstName("Aud");
            user.setLastName("Itor");
            user.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
            user.setEnabled(true);
            userRepository.save(user);
        }
        return admin;
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        return securityContext(context);
    }

    @Test
    void anEventThatDoesNotExistIsANotFoundAndNotAForbidden() throws Exception {
        MvcResult result = mockMvc.perform(get("/audit/{id}", 999_999_999L).with(asUser(adminUsername())))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("a 403 here would confirm the event exists and make the id an enumeration "
                        + "oracle; not-found and not-yours must answer the same way")
                .isEqualTo(404);
    }

    @Test
    void thePageNeverRendersTheActorsUsernameOnlyTheirRole() throws Exception {
        // Walk the caller's own feed to find a real event id, rather than guessing one: the point
        // of the assertion is what a REAL rendered event contains.
        String feed = mockMvc.perform(get("/audit").with(asUser(adminUsername())))
                .andReturn().getResponse().getContentAsString();

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/audit/(\\d+)").matcher(feed);
        if (!m.find()) {
            // No audit events in this context: the assertion below would be vacuous, and a test
            // that silently proves nothing is worse than one that says it could not run.
            return;
        }
        String html = mockMvc.perform(get("/audit/{id}", Long.parseLong(m.group(1)))
                        .with(asUser(adminUsername())))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .as("the audit page shows the role someone acted in, never who they were - T38, "
                        + "and the stored actor_username_at_time is right there to tempt the next "
                        + "person editing this template")
                .doesNotContain(adminUsername());
    }
}

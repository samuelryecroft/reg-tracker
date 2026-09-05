package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
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
 * T119 spec §7b (4d, users): a list of people, so R-Q12 rules the table out - one card rendering,
 * not two.
 *
 * <p>Mirrors {@code ChildListLayoutIntegrationTest} deliberately, because this page carried the
 * same defect 6a did and for the same reason: a {@code <table>} and a card stack over one list,
 * only ever one of them visible, so the two renderings could disagree without anyone seeing it.
 * <b>They already had.</b> The table wrote an absent organisation as an em dash; the stack dropped
 * the row entirely. Two answers to "what does no organisation look like", neither wrong on its own
 * screen, and no viewport that shows both.
 *
 * <p>The role-chip assertion is the part that would otherwise go unguarded: chips are the 4d delta,
 * and a chip that renders {@code HOME_STAFF} instead of "Home Staff" is a screen that looks
 * finished and reads like a database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserListLayoutIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    private User savedUser(String username, String first, String last, boolean enabled, Role... roles) {
        User user = new User();
        user.setUsername(username);
        user.setFirstName(first);
        user.setLastName(last);
        user.setRoles(new HashSet<>(Set.of(roles)));
        user.setOrganisation(seededSupplier());
        user.setEnabled(enabled);
        return userRepository.save(user);
    }

    private String usersPageAs(String username) throws Exception {
        return mockMvc.perform(get("/admin/users").with(asUser(username)))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theUserListRendersNoTableAtAll() throws Exception {
        String suffix = "-" + System.nanoTime();
        String admin = "t4d-admin" + suffix;
        savedUser(admin, "Ada", "Admin" + suffix, true, Role.ADMIN);

        String html = usersPageAs(admin);

        // R-Q12 (spec §7b): users are people, so the table that used to render alongside the card
        // stack is gone, not merely hidden at this viewport.
        assertThat(html).doesNotContain("<table");
        assertThat(html).contains(admin);
    }

    @Test
    void rolesRenderAsChipsCarryingTheirDisplayNameNotTheEnumConstant() throws Exception {
        String suffix = "-" + System.nanoTime();
        String admin = "t4d-admin-chips" + suffix;
        savedUser(admin, "Ada", "Admin" + suffix, true, Role.ADMIN);
        savedUser("t4d-coord" + suffix, "Rina", "Kowalczyk" + suffix, true,
                Role.COORDINATOR, Role.REVIEWER);

        String html = usersPageAs(admin);

        assertThat(html).contains("Coordinator").contains("Reviewer");
        // The enum constant must not reach the page. Asserted on the underscore form because the
        // single-word constants are indistinguishable from their own labels once upper-cased, so
        // only HOME_STAFF and ORG_ADMIN can carry this negative - and they are the two the
        // derive-from-name() shortcut would have got wrong anyway.
        assertThat(html).doesNotContain("HOME_STAFF").doesNotContain("ORG_ADMIN");
    }

    @Test
    void aDisabledAccountSaysSoInWordsAndNotOnlyByBeingDimmed() throws Exception {
        String suffix = "-" + System.nanoTime();
        String admin = "t4d-admin-disabled" + suffix;
        savedUser(admin, "Ada", "Admin" + suffix, true, Role.ADMIN);
        savedUser("t4d-off" + suffix, "Tim", "Fenwick" + suffix, false, Role.VIEWER);

        String html = usersPageAs(admin);

        // WCAG 1.4.1: .case-inactive dims the row, and dimming alone is a colour-only signal. The
        // chip is what actually carries the meaning, so it is the chip this asserts - a test that
        // only checked for the dimming class would pass on a row nobody can read the state of.
        assertThat(html).contains("case-inactive");
        assertThat(html).contains(">Disabled<");
    }
}

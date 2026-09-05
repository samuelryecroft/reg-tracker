package ninja.samryecroft.returnhome.tracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T193 (PILOT-GATE, spec §7f D-4b-11): {@code children/list.html} took names through the
 * {@link ninja.samryecroft.returnhome.tracker.child.ChildIdentity} projection, so the page is a
 * masked surface - but read date of birth and local case reference straight off the entity in both
 * renderings (the table and the card stack), printing both {@code @Encrypted} Article-9 fields in
 * the clear beside a masked name.
 *
 * <p>Only the birth date is actually gated: masked shows the word "Hidden", never the value (not
 * even a hidden-but-present one - checked by asserting the real value is ABSENT from the whole
 * response, not just invisible), and revealed shows the real value in both renderings. The case
 * reference is routed through the same server-side projection (so it is never read off the entity
 * directly either) but is deliberately NEVER masked - Kevin's masked name label already shows it
 * on every row by design, so a masked "Hidden" in the column would be a FALSE one, about a value
 * already on screen (Creed's T193 follow-up correction, after an earlier version of this fix
 * masked both fields "for consistency").
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChildListFieldMaskingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private String username;
    private String caseReference;
    private MockHttpSession session;

    @BeforeEach
    void seedAChildWithARealBirthDateAndCaseReference() {
        String suffix = "-" + System.nanoTime();
        Organisation careProviderOrg = seededCareProvider();
        Home home = new Home();
        home.setName("T193 House" + suffix);
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);

        caseReference = "CH-T193" + suffix;
        Child child = new Child();
        child.setFirstName("Jordan");
        child.setLastName("Masked");
        child.setDateOfBirth(LocalDate.of(2013, 7, 22));
        child.setLocalCaseReference(caseReference);
        child.setHome(home);
        childRepository.save(child);

        username = "t193-staff" + suffix;
        User staff = new User();
        staff.setUsername(username);
        staff.setLastName("Staff");
        staff.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        staff.setHomes(new HashSet<>(Set.of(home)));
        staff.setEnabled(true);
        userRepository.save(staff);

        session = new MockHttpSession();
    }

    private RequestPostProcessor asUser() {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    @Test
    void maskedGatesTheBirthDateButNeverFalselyHidesTheAlreadyVisibleCaseReference() throws Exception {
        String html = mockMvc.perform(get("/children").with(asUser()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Never a hidden-but-present value: the real birth date must not appear anywhere in the
        // response, not merely be styled invisible - "it was in the DOM but hidden" is exactly the
        // position Creed's spec (via ChildIdentity's own javadoc) rejects.
        assertThat(html).doesNotContain("22 Jul 2013");
        // Only the birth date is gated - two "Hidden"s (table + stack). An earlier version of this
        // fix also gated the case reference and asserted four; that was wrong (Creed's T193
        // follow-up) - the masked LABEL already shows the reference by design ("J.M. · CH-T193..."),
        // so a masked "Hidden" in the column would be a FALSE one, about a value already on screen.
        assertThat(occurrencesOf(html, "Hidden")).isEqualTo(2);
        // The real case reference appears four times even while masked, none of them gated: the
        // masked LABEL renders once per rendering context (table + stack), same as every other
        // field on this page, and the never-gated COLUMN renders once per context too (table +
        // stack) - 2 + 2.
        assertThat(occurrencesOf(html, caseReference)).isEqualTo(4);
    }

    @Test
    void revealedShowsTheRealValuesInBothRenderings() throws Exception {
        mockMvc.perform(post("/account/reveal-names").with(asUser()).with(csrf()).session(session)
                        .param("returnTo", "/children"))
                .andExpect(status().is3xxRedirection());

        String html = mockMvc.perform(get("/children").with(asUser()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(occurrencesOf(html, "22 Jul 2013")).isEqualTo(2); // table + stack
        // Revealed, the label becomes ChildIdentity.of's full-name form (getFullName() alone, no
        // case reference - that asymmetry is D-4b-3/D-4b-8, a separate, already-ruled ticket) - so
        // today the reference appears twice: the never-gated column, once per rendering context
        // (table + stack), never via the label.
        // TRIPWIRE (Creed, T193 follow-up): once ChildIdentity goes additive, the revealed label
        // will ALSO carry the reference once per context (table + stack), and this count becomes 4
        // - the expected, correct effect of that separate change, not a regression to "fix" back to
        // 2. That is also the moment the case-reference COLUMN becomes fully redundant in the
        // revealed view too (D-4b-3's own column-drop question) - if you're touching this number,
        // you're touching that question.
        assertThat(occurrencesOf(html, caseReference)).isEqualTo(2); // table + stack
        assertThat(html).doesNotContain("Hidden");
    }

    private static int occurrencesOf(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}

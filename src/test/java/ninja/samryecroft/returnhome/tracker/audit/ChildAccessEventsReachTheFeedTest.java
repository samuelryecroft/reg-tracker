package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T292: opening a child's record is recorded against the CHILD, and until now the feed dropped every
 * one of those rows because it could not resolve them to an interview request.
 *
 * <p><strong>It was a retrieval failure, not a recording one.</strong> The rows were always written,
 * immutable and retained - so accountability survived and only DETECTION did not, because detection
 * needs someone able to look. We had not lost the data, we had lost the route.
 *
 * <p><strong>The scoping arm matters more than the feature arm.</strong> A second resolution path is
 * a second place scoping can be got wrong, and getting it wrong would open a REACH problem in order
 * to close a RETRIEVAL one - strictly worse than the bug. So the deny assertion runs through the
 * controller, where {@code requestsInScope(principal)} is the actual access control; a test that
 * called the service with a hand-built list would be checking the mechanism and not the rule.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChildAccessEventsReachTheFeedTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InterviewRequestRepository interviewRequestRepository;
    @Autowired
    private AuditHistoryService auditHistoryService;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private String suffix;
    private Long alphaChildId;
    private Long betaChildId;
    private Long otherOrgChildId;
    private String orgAdmin;
    private String viewerOfAlphaOnly;
    private String otherOrgAdmin;

    @BeforeEach
    void seedTwoHomesInOneOrganisationAndOneUnrelatedProvider() {
        suffix = "-" + System.nanoTime();

        Organisation ours = saveOrg("T292 Ours" + suffix);
        Organisation theirs = saveOrg("T292 Theirs" + suffix);
        // TWO HOMES IN ONE ORGANISATION is the fixture that matters, and the cross-organisation pair
        // alone would not have been enough: events are already bounded by organisation upstream
        // (findByOrganisationIdIn), so another provider's row never reaches the resolution path at
        // all. Only a principal scoped BELOW the organisation - a viewer with one home - can tell
        // whether the child path re-derives scope correctly or quietly widens it.
        Home alpha = saveHome("T292 Alpha House" + suffix, ours);
        Home beta = saveHome("T292 Beta House" + suffix, ours);
        Home gamma = saveHome("T292 Gamma House" + suffix, theirs);

        alphaChildId = saveChild("Casey", "AlphaSurname" + suffix, "CH-ALPHA" + suffix, alpha);
        betaChildId = saveChild("Alex", "BetaSurname" + suffix, "CH-BETA" + suffix, beta);
        otherOrgChildId = saveChild("Sam", "GammaSurname" + suffix, "CH-GAMMA" + suffix, gamma);

        // ORG_ADMIN in a care provider scopes by organisation; VIEWER scopes by the homes they hold.
        // A COORDINATOR sitting in a care provider would fall through requestsInScope to the
        // SUPPLIER-scoped branch and get an EMPTY feed - correct behaviour, pinned by
        // SupplierScopeAuditFeedIntegrationTest, and it would have made every assertion here pass or
        // fail for a reason with nothing to do with T292.
        orgAdmin = "t292-orgadmin" + suffix;
        viewerOfAlphaOnly = "t292-viewer-alpha" + suffix;
        otherOrgAdmin = "t292-other-orgadmin" + suffix;
        userRepository.save(user(orgAdmin, Role.ORG_ADMIN, ours, null));
        userRepository.save(user(viewerOfAlphaOnly, Role.VIEWER, ours, alpha));
        userRepository.save(user(otherOrgAdmin, Role.ORG_ADMIN, theirs, null));

        String alphaStaff = "t292-alpha-staff" + suffix;
        String betaStaff = "t292-beta-staff" + suffix;
        String gammaStaff = "t292-gamma-staff" + suffix;
        userRepository.save(user(alphaStaff, Role.HOME_STAFF, null, alpha));
        userRepository.save(user(betaStaff, Role.HOME_STAFF, null, beta));
        userRepository.save(user(gammaStaff, Role.HOME_STAFF, null, gamma));

        // A request each, because a child reaches the feed only through the principal-bounded request
        // set - that intersection IS the access rule, and a child with no request is outside it.
        raiseRequest(alphaStaff, alphaChildId);
        raiseRequest(betaStaff, betaChildId);
        raiseRequest(gammaStaff, otherOrgChildId);

        // The access events, emitted by the PRODUCTION path: opening the child page is what records
        // one. A hand-built row would prove the feed can render something we assembled ourselves.
        openTheChildPage(alphaStaff, alphaChildId);
        openTheChildPage(betaStaff, betaChildId);
        openTheChildPage(gammaStaff, otherOrgChildId);
    }

    /** The gap itself: before T292 this link appeared on no screen in the application. */
    @Test
    void theAccessRowNowReachesTheFeedAndLinksToTheChild() throws Exception {
        assertThat(feedAs(orgAdmin)).contains("/children/" + alphaChildId);
    }

    /**
     * <strong>THE SCOPING ARM, and it is the reason the fixture has two homes in ONE organisation.</strong>
     *
     * <p>A viewer holds Alpha and not Beta. Both children sit in the same organisation, so Beta's
     * access event IS in the event list this viewer's feed iterates - the organisation bound upstream
     * does nothing here. The only thing keeping it off their screen is that the child path re-derives
     * its scope from the same principal-bounded request set. Resolve the child any other way - a
     * lookup by home, by organisation, or an "attach it to whatever request we have so it shows up"
     * fallback - and this goes red. <strong>Verified by arming it: with that fallback in place this
     * assertion fails, and the cross-organisation one below does not.</strong>
     *
     * <p>Both directions in one test on purpose. A feed that had simply stopped resolving child rows
     * would satisfy the deny half perfectly.
     */
    @Test
    void aViewerHoldingOneHomeSeesItsChildAccessRowAndNotTheOtherHomesInTheSameOrganisation() throws Exception {
        String html = feedAs(viewerOfAlphaOnly);

        assertThat(html).contains("/children/" + alphaChildId);
        assertThat(html).doesNotContain("/children/" + betaChildId);
    }

    /** The organisation boundary, which is held upstream rather than here - asserted so it stays held. */
    @Test
    void anotherOrganisationsChildAccessRowIsNotInOurFeed() throws Exception {
        assertThat(feedAs(orgAdmin)).doesNotContain("/children/" + otherOrgChildId);
    }

    /**
     * And the deny above is not vacuous. Without this, a feed that had simply stopped resolving child
     * rows at all - the pre-T292 behaviour - would satisfy every scoping test on this page.
     */
    @Test
    void theOtherProvidersOwnAdminDoesSeeThatSameRow() throws Exception {
        assertThat(feedAs(otherOrgAdmin)).contains("/children/" + otherOrgChildId);
    }

    /**
     * A3 is untouched: the disclosure scope still excludes access events, child-targeted ones
     * included. The new resolution path widened WHICH access rows can be resolved, not WHICH SCOPES
     * ask for them - and those are the two things it would be easy to conflate.
     */
    @Test
    void theDisclosureScopeStillHasNoChildAccessRows() {
        List<InterviewRequest> ourRequests = interviewRequestRepository.findAllDetailed().stream()
                .filter(request -> alphaChildId.equals(request.getChild().getId()))
                .toList();

        assertThat(auditHistoryService.caseActivityFeed(ourRequests, null, null, null,
                AuditFeedScope.CASE_ACTIVITY_ONLY))
                .as("the CSV's scope must not acquire child access rows by way of the new path")
                .allSatisfy(row -> assertThat(row.childId()).isNull());
        // ...and the widest scope does resolve one, so the assertion above is about the SCOPE rather
        // than about the feed being empty for this fixture.
        assertThat(auditHistoryService.caseActivityFeed(ourRequests, null, null, null,
                AuditFeedScope.WITH_ACCESS_EVENTS))
                .anySatisfy(row -> assertThat(row.childId()).isEqualTo(alphaChildId));
    }

    private String feedAs(String username) throws Exception {
        return mockMvc.perform(get("/audit").with(asUser(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void openTheChildPage(String staffUsername, Long childId) {
        try {
            mockMvc.perform(get("/children/{id}", childId).with(asUser(staffUsername)))
                    .andExpect(status().isOk());
        } catch (Exception e) {
            throw new IllegalStateException("Could not record the child-page access", e);
        }
    }

    private void raiseRequest(String staffUsername, Long childId) {
        try {
            mockMvc.perform(post("/requests").with(asUser(staffUsername)).with(csrf())
                            .param("childId", childId.toString())
                            .param("returnedAt", "2026-07-16T20:30"))
                    .andExpect(status().is3xxRedirection());
        } catch (Exception e) {
            throw new IllegalStateException("Could not seed the interview request", e);
        }
    }

    private Organisation saveOrg(String name) {
        Organisation org = new Organisation();
        org.setName(name);
        org.setType(OrgType.CARE_PROVIDER);
        return organisationRepository.save(org);
    }

    private Home saveHome(String name, Organisation org) {
        Home home = new Home();
        home.setName(name);
        home.setOrganisation(org);
        return homeRepository.save(home);
    }

    private Long saveChild(String firstName, String lastName, String caseReference, Home home) {
        Child child = new Child();
        child.setFirstName(firstName);
        child.setLastName(lastName);
        child.setLocalCaseReference(caseReference);
        child.setDateOfBirth(LocalDate.of(2012, 2, 2));
        child.setHome(home);
        return childRepository.save(child).getId();
    }

    private User user(String username, Role role, Organisation organisation, Home home) {
        User user = new User();
        user.setUsername(username);
        user.setLastName(username);
        user.setRoles(new HashSet<>(Set.of(role)));
        user.setOrganisation(organisation);
        user.setHomes(home == null ? new HashSet<>() : new HashSet<>(Set.of(home)));
        user.setEnabled(true);
        user.setCanExport(true);
        return userRepository.save(user);
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        return securityContext(context);
    }
}

package ninja.samryecroft.returnhome.tracker.organisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
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
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T267 on the screens: an administrator is told DURING SETUP that an organisation cannot yet do its
 * job, rather than a coordinator discovering it on first use.
 *
 * <p><b>Two surfaces because there are two administrators and only one of them can act.</b> An org
 * admin sees their own organisation on {@code /admin/users}, the screen with the Add user button on
 * it; the platform admin sees every organisation on the 4e tree. Oscar's T266 ruling turns on
 * exactly that split - a coordinator who is not an administrator can only be told to ask somebody
 * else - and it decides where a setup-time notice is worth putting.
 *
 * <p><b>Copy is asserted with whitespace collapsed</b>, which T266 taught the hard way: a sentence
 * that is perfectly correct on the page can be split across source lines by the template, so a
 * contiguous-substring assertion fails while nothing is wrong. These assertions are about the
 * sentence a person reads, and a person does not see source line breaks.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AnAdministratorIsToldTheirOrganisationCannotWorkYetTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private String suffix;
    private Organisation careProvider;
    private Home home;
    private String orgAdminUsername;
    private String platformAdminUsername;

    @BeforeEach
    void seedAProviderWithAnAdministratorAndNobodyElse() {
        suffix = "-" + System.nanoTime();
        Organisation supplier = saveOrg("T267 Screen Supplier" + suffix, OrgType.SUPPLIER, null);
        careProvider = saveOrg("T267 Screen Provider" + suffix, OrgType.CARE_PROVIDER, supplier);
        home = new Home();
        home.setName("T267 Screen House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        orgAdminUsername = "t267-orgadmin" + suffix;
        saveUser(orgAdminUsername, Set.of(Role.ORG_ADMIN), careProvider, null);
        platformAdminUsername = "t267-platformadmin" + suffix;
        // CARRIES AN ORGANISATION, deliberately, and it is the whole reason the platform-admin arm
        // below is worth running. With organisation null the notice is absent no matter how the
        // subject is computed - a naive "return principal.getOrganisationId()" would pass, and the
        // guard would be decorative. This account shape is real rather than contrived: UserService
        // documents its mirror, "an ORG_ADMIN with no organisation is what a half-applied data
        // repair leaves behind", and this is the same leftover pointed the other way.
        saveUser(platformAdminUsername, Set.of(Role.ADMIN), careProvider, null);
    }

    /**
     * The org admin's own screen. The organisation has an administrator and nobody else, which is
     * what an organisation looks like on the first afternoon of its existence.
     */
    @Test
    void theOrgAdminIsToldWhichRoleIsMissingOnTheScreenThatCanFixIt() throws Exception {
        String page = collapsed(pageAs(orgAdminUsername, "/admin/users"));

        assertThat(page).contains("Some of this organisation’s roles are not filled");
        // NAMED, not counted. "Something is missing" is not something anyone can act on.
        assertThat(page).contains("needs at least one Home Staff account");
        assertThat(page).as("the route out is on the same screen").contains("/admin/users/new");
    }

    /**
     * THE POSITIVE CONTROL, and without it every assertion above is satisfied by a banner that is
     * always on. Add the missing person and the notice goes.
     */
    @Test
    void andTheNoticeGoesWhenTheMissingPersonIsAdded() {
        assertThat(collapsed(pageAs(orgAdminUsername, "/admin/users")))
                .contains("Some of this organisation’s roles are not filled");

        saveUser("t267-staff" + suffix, Set.of(Role.HOME_STAFF), null, home);

        assertThat(collapsed(pageAs(orgAdminUsername, "/admin/users")))
                .doesNotContain("Some of this organisation’s roles are not filled");
    }

    /**
     * CREED'S MEASURED CONSTRAINT, GUARDED RATHER THAN TAKEN ON FAITH.
     *
     * <p>{@code listVisible} has three branches, and the platform admin's is
     * {@code findAllWithHome()} - <b>every user on the platform, unscoped</b>. So this page has a
     * single organisation as its subject only in the two org-admin branches, and a banner reading
     * "this organisation" here would name one that is not what the reader is looking at. The
     * organisation in question is genuinely unstaffed, AND this platform admin carries it as their
     * own organisation, so a subject computed from the principal's organisation id alone would
     * render the notice here. See the fixture for why that matters.
     *
     * <p>The platform admin is not being told less: they are told the same thing about every
     * organisation at once, on the tree, which is a question this page cannot answer.
     */
    @Test
    void thePlatformAdminsUserListCarriesNoBannerBecauseItHasNoSingleSubject() {
        String page = collapsed(pageAs(platformAdminUsername, "/admin/users"));

        assertThat(page).as("the unstaffed organisation is on this page, so the notice had a reason "
                        + "to render and did not")
                .contains("T267 Screen Provider" + suffix);
        assertThat(page).doesNotContain("Some of this organisation’s roles are not filled");
    }

    /**
     * The tier, which is a ruling rather than a preference and has moved on this codebase before
     * (T286 walked banners between warn and err across the product).
     *
     * <p>Creed's §5h.4 rule: <b>an empty collection with a next action is not a warning, it is a
     * state the system reached correctly.</b> An organisation missing a role is exactly that, and
     * the next action is the Add user button below it. Nothing has failed and nobody has done
     * anything wrong.
     */
    @Test
    void theNoticeIsInfoTierAndOffersNoSecondAddUserLink() {
        String page = pageAs(orgAdminUsername, "/admin/users");
        int at = page.indexOf("Some of this organisation’s roles are not filled");
        int bannerStart = page.lastIndexOf("<div class=\"banner", at);
        String openingTag = page.substring(bannerStart, page.indexOf('>', bannerStart) + 1);

        assertThat(openingTag).contains("banner info").doesNotContain("banner warn").doesNotContain("banner err");

        String banner = page.substring(bannerStart, page.indexOf("</div>", page.indexOf("</div>", at) + 1));
        assertThat(banner).as("the departure from the precedent: Add user is this page's primary "
                        + "control and sits inches below, so a link here ships two of them")
                .doesNotContain("/admin/users/new");
        assertThat(collapsed(page)).as("and the one that IS the route out is still there")
                .contains("/admin/users/new");
    }

    /** The platform admin's tree says the same thing about every organisation at once. */
    @Test
    void theTreeNamesWhatEachOrganisationIsMissing() {
        String tree = collapsed(pageAs(platformAdminUsername, "/admin/organisations"));

        assertThat(tree).contains("T267 Screen Provider" + suffix);
        assertThat(tree).contains("Missing: Home Staff");
        assertThat(tree).as("and the supplier's three, which is a different statement")
                .contains("Missing: Coordinator, Visitor, Reviewer");
    }

    /**
     * THE TREE'S NEGATIVE CONTROL, and it needs a per-ROW look rather than a per-page one.
     *
     * <p>Every other organisation on this screen is unready - the platform's seeded ones have nobody
     * in them either - so "the page does not say it" is a sentence that can never be true here, and
     * an assertion written that way would be answering a different question than the one it appears
     * to ask. This reads the identity block of ONE row: the provider that now has its home staff
     * must say nothing, on a page that is still saying it about everybody else.
     */
    @Test
    void aProviderThatHasItsPeopleSaysNothingOnItsOwnRow() {
        saveUser("t267-staff-row" + suffix, Set.of(Role.HOME_STAFF), null, home);

        String tree = pageAs(platformAdminUsername, "/admin/organisations");
        // FROM THE TREE, not from the page. The shell nav renders the signed-in user's own
        // organisation name, so a page-wide indexOf can anchor OUTSIDE the tree entirely and the
        // block then runs to the first row of somebody else's - which is how this assertion failed
        // the moment the fixture gave the platform admin an organisation. The test was right and the
        // anchor was wrong.
        int treeStart = tree.indexOf("class=\"org-tree\"");
        assertThat(treeStart).as("the tree itself has to be on the page").isGreaterThan(-1);
        int nameAt = tree.indexOf("T267 Screen Provider" + suffix, treeStart);
        assertThat(nameAt).as("the row has to be on the page for its absence to mean anything")
                .isGreaterThan(-1);
        String identityBlock = tree.substring(nameAt, tree.indexOf("case-tags", nameAt));

        assertThat(collapsed(identityBlock)).doesNotContain("Missing:");
        assertThat(collapsed(tree)).as("while the screen is still saying it about the unstaffed ones")
                .contains("Missing:");
    }

    /**
     * THE CARD'S OWN CONDITION, ON THE SCREEN: "not operational" must be DISTINGUISHED from the
     * activation gate, not merged with it.
     *
     * <p>This organisation is ACTIVE - its encryption key is confirmed, so the thing
     * {@code OrgStatus} talks about is fine - and it still has nobody in it. Both statements have to
     * be on the row, saying different things, because they have different remedies: an operator
     * provisions a key, an administrator adds a person.
     *
     * <p><b>The structural half is the one worth having.</b> The readiness line must not be a chip
     * in the tag row beside the status chip - two chips there read as one status field with two
     * values, which is the conflation arriving through layout rather than through code, and no copy
     * assertion would ever notice.
     */
    @Test
    void anActiveOrganisationWithNobodyInItSaysBothThingsSeparately() {
        careProvider.setStatus(OrgStatus.ACTIVE);
        organisationRepository.saveAndFlush(careProvider);

        String tree = pageAs(platformAdminUsername, "/admin/organisations");

        assertThat(collapsed(tree)).as("the status chip still says the key is fine").contains(">Active<");
        assertThat(collapsed(tree)).as("and the readiness line still says the people are not")
                .contains("Missing: Home Staff");

        int at = tree.indexOf("Missing: Home Staff");
        int spanStart = tree.lastIndexOf("<span", at);
        String enclosing = tree.substring(spanStart, tree.indexOf('>', spanStart) + 1);
        assertThat(enclosing)
                .as("readiness belongs with the membership facts, not in the tag row - see the "
                        + "template comment for why this is the substance of the card and not a "
                        + "styling preference")
                .contains("case-meta")
                .doesNotContain("class=\"status")
                .doesNotContain("class=\"tag");
    }

    private String pageAs(String username, String path) {
        try {
            return mockMvc.perform(get(path).with(asUser(username)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
        } catch (Exception e) {
            throw new AssertionError("could not render " + path + " as " + username, e);
        }
    }

    /** See the class note: these assertions are about the sentence, not about the markup's shape. */
    private static String collapsed(String html) {
        return html.replaceAll("\\s+", " ");
    }

    private void saveUser(String username, Set<Role> roles, Organisation organisation, Home theirHome) {
        User user = new User();
        user.setUsername(username);
        user.setLastName(username);
        user.setRoles(new HashSet<>(roles));
        user.setOrganisation(organisation);
        user.setHomes(theirHome == null ? new HashSet<>() : new HashSet<>(Set.of(theirHome)));
        user.setEnabled(true);
        userRepository.saveAndFlush(user);
    }

    private Organisation saveOrg(String name, OrgType type, Organisation servedBy) {
        Organisation org = new Organisation();
        org.setName(name);
        org.setType(type);
        org.setSupplierOrganisation(servedBy);
        return organisationRepository.saveAndFlush(org);
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        return securityContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())));
    }
}

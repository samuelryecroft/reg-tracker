package ninja.samryecroft.returnhome.tracker.organisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T168(b) driven through the real endpoint: an organisation that is not ACTIVE cannot take a child's
 * record.
 *
 * <p>This is the class-removal half of T168. The 503 that ships in #63 makes the failure GRACEFUL -
 * it happens at write time, in front of whoever was trying to record a child. The guard makes it
 * UNREACHABLE, by refusing at the point the person can still do something about it. Both exist on
 * purpose: this is the gate, that is the safety net.
 *
 * <p>Server-side, not the button. A hidden control is not an access control - the same point the
 * T117 role-matrix tests make - and the failure this prevents is a safeguarding one, so it is
 * asserted where it is actually enforced.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrganisationActivationGuardIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Home pendingHome;
    private Home activeHome;
    private String suffix;

    /**
     * Two organisations, and the pending one is CREATED rather than a seeded one poked into shape.
     * That is deliberate twice over: it exercises the real path - a newly created organisation is
     * PENDING because {@code Organisation.status} initialises there - and it leaves the V5 reference
     * organisations untouched, so this class cannot make a later test in it depend on the order it
     * ran in. An earlier version mutated the shared seeded organisation and did exactly that.
     */
    @BeforeEach
    void seed() {
        suffix = "-" + System.nanoTime();

        Organisation pendingProvider = new Organisation();
        pendingProvider.setName("Not Yet Active" + suffix);
        pendingProvider.setType(OrgType.CARE_PROVIDER);
        pendingProvider.setSupplierOrganisation(seededSupplier());
        pendingProvider = organisationRepository.save(pendingProvider);

        pendingHome = new Home();
        pendingHome.setName("Pending House" + suffix);
        pendingHome.setOrganisation(pendingProvider);
        pendingHome = homeRepository.save(pendingHome);

        activeHome = new Home();
        activeHome.setName("Active House" + suffix);
        activeHome.setOrganisation(seededCareProvider());
        activeHome = homeRepository.save(activeHome);

        User staff = new User();
        staff.setUsername("guard-staff" + suffix);
        staff.setPassword(passwordEncoder.encode("password123"));
        staff.setFirstName("Guard");
        staff.setLastName("Staff");
        staff.setEmail("guard" + suffix + "@example.test");
        staff.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        staff.setHomes(new HashSet<>(Set.of(pendingHome, activeHome)));
        userRepository.save(staff);

        User admin = new User();
        admin.setUsername("guard-admin" + suffix);
        admin.setPassword(passwordEncoder.encode("password123"));
        admin.setFirstName("Guard");
        admin.setLastName("Admin");
        admin.setEmail("guard-admin" + suffix + "@example.test");
        admin.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        admin.setHomes(new HashSet<>());
        userRepository.save(admin);
    }

    @Test
    void anOrganisationThatIsNotActiveCannotHaveAChildAdded() throws Exception {
        long before = childRepository.count();

        String html = mockMvc.perform(post("/children")
                        .with(asUser("guard-staff" + suffix)).with(csrf())
                        .param("firstName", "Refused")
                        .param("lastName", "Child")
                        .param("dateOfBirth", LocalDate.of(2012, 1, 1).toString())
                        .param("homeId", pendingHome.getId().toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(childRepository.count())
                .as("the record must not be created for an organisation that cannot encrypt it")
                .isEqualTo(before);

        // Refused with something the person can act on, at the point they can still act - which is
        // the whole difference between this guard and the write-time 503 it backs up.
        assertThat(html).contains("not yet active");
    }

    /**
     * D-5d-3 (spec §7g): the case the guard's own message used to get wrong. A single-home
     * HOME_STAFF user never renders the {@code homeId} field at all ({@code needsHomePicker} is
     * false), so a rejection attached to that field as a {@code FieldError} would point a banner
     * link at a control that is not on the page. Asserted through the real endpoint, not the
     * controller in isolation, so a future change to which users get a picker cannot silently
     * re-break this without failing here too.
     */
    @Test
    void aSingleHomeUserWithNoHomeIdFieldOnThePageStillSeesTheBannerWithNoDeadLink() throws Exception {
        String singleHomeStaffUsername = "guard-single-staff" + suffix;
        User singleHomeStaff = new User();
        singleHomeStaff.setUsername(singleHomeStaffUsername);
        singleHomeStaff.setPassword(passwordEncoder.encode("password123"));
        singleHomeStaff.setFirstName("Guard");
        singleHomeStaff.setLastName("Single");
        singleHomeStaff.setEmail(singleHomeStaffUsername + "@example.test");
        singleHomeStaff.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        singleHomeStaff.setHomes(new HashSet<>(Set.of(pendingHome)));
        userRepository.save(singleHomeStaff);

        String html = mockMvc.perform(post("/children")
                        .with(asUser(singleHomeStaffUsername)).with(csrf())
                        .param("firstName", "Refused")
                        .param("lastName", "Child")
                        .param("dateOfBirth", LocalDate.of(2012, 1, 1).toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("not yet active");
        // The field this used to be a FieldError on is genuinely absent from this user's page.
        assertThat(html).doesNotContain("id=\"homeId\"");
    }

    /**
     * The control. Without it, a guard that refused EVERY add-child would pass the test above while
     * being catastrophically wrong - and the V19 backfill (existing organisations to ACTIVE, not
     * PENDING) is exactly the line where that mistake would be made.
     */
    @Test
    void anActiveOrganisationIsUnaffected() throws Exception {
        long before = childRepository.count();

        mockMvc.perform(post("/children")
                        .with(asUser("guard-staff" + suffix)).with(csrf())
                        .param("firstName", "Allowed")
                        .param("lastName", "Child")
                        .param("dateOfBirth", LocalDate.of(2012, 1, 1).toString())
                        .param("homeId", activeHome.getId().toString()))
                .andExpect(status().is3xxRedirection());

        assertThat(childRepository.count()).isEqualTo(before + 1);
    }

    /**
     * V19's backfill, asserted rather than assumed - it is the one line in the migration that can
     * hurt production, because backwards it blocks child creation across the whole estate on
     * deploy. Scoped to the organisations the migration actually backfilled (the V5 reference rows),
     * since anything this class creates afterwards is correctly PENDING.
     */
    @Test
    void theMigrationLeftPreExistingOrganisationsActive() {
        assertThat(seededSupplier().getStatus()).isEqualTo(OrgStatus.ACTIVE);
        assertThat(seededCareProvider().getStatus()).isEqualTo(OrgStatus.ACTIVE);
    }

    /**
     * T168(b), the floor under the narrowing. {@link OrganisationLifecycleService} requires a KEK for
     * CARE_PROVIDERs only, which is correct BECAUSE every encrypted entity resolves its owning
     * organisation through {@code home.getOrganisation()} and homes belong to care providers.
     *
     * <p>Kevin's review: nothing enforced the second half. The admin form's dropdown is filtered to
     * care providers, but <b>a filtered dropdown shapes the form, not the POST</b> - a platform admin
     * could post a supplier's id, and V6's foreign key does not care about the type either. That is
     * the most common way an invariant gets believed without existing, because every screenshot of
     * the working system shows it holding.
     *
     * <p>It mattered here specifically because the unconditional KEK check I removed had been
     * catching this by accident: before that change a supplier with a home was un-activatable, so no
     * child was ever created into one. Removing a bug removed a net, and this is the net put back
     * deliberately - as a constraint on the thing that was actually wrong, rather than as a check
     * that made correct configurations impossible.
     */
    @Test
    void aHomeCannotBeHungOffASupplierOrganisation() throws Exception {
        long before = homeRepository.count();

        String html = mockMvc.perform(post("/admin/homes")
                        .with(asUser("guard-admin" + suffix)).with(csrf())
                        .param("name", "Misfiled House" + suffix)
                        .param("organisationId", seededSupplier().getId().toString())
                        .param("addressLine1", "1 Wrong Street")
                        .param("postcode", "AB1 2CD"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(homeRepository.count())
                .as("a home under a supplier would make the CARE_PROVIDER-only KEK check wrong, and "
                        + "its children would fail closed against a key that never should exist")
                .isEqualTo(before);
        assertThat(html).contains("care provider");
    }

    /** The paired positive: the same POST against a care provider must still work. */
    @Test
    void aHomeUnderACareProviderIsStillAccepted() throws Exception {
        long before = homeRepository.count();

        mockMvc.perform(post("/admin/homes")
                        .with(asUser("guard-admin" + suffix)).with(csrf())
                        .param("name", "Correctly Filed House" + suffix)
                        .param("organisationId", seededCareProvider().getId().toString())
                        .param("addressLine1", "1 Right Street")
                        .param("postcode", "AB1 2CD"))
                .andExpect(status().is3xxRedirection());

        assertThat(homeRepository.count()).isEqualTo(before + 1);
    }

    /**
     * There is no column default any more, so an insert that bypasses the entity fails LOUDLY.
     *
     * <p><b>This assertion is the flip V19 and this test both said would come.</b> The previous
     * version pinned the default to PENDING and said so explicitly: "the end state we still want is
     * no default at all... that ships in a later release once no old jar can be running, and this
     * assertion flips to null then. A test that must be edited when the schema intentionally changes
     * is working correctly." T172 established the precondition and V20 did the drop, so it has.
     *
     * <p><b>Why the default was there at all</b>, because this test is now the only place in the
     * test tree that would tell a reader: the DB-plane job runs Flyway before the new jar is live,
     * so an old jar briefly writes to the new schema without knowing the column. With no default
     * that is a NOT NULL violation - an admin's org-create 500s mid-onboarding. V19 chose PENDING
     * over ACTIVE for that window so a row from an unaware jar lands safe and still goes through the
     * KEK gate. <b>That reasoning is not obsolete; it is the reason a default must never come back
     * as ACTIVE if one is ever needed again.</b>
     *
     * <p>What changes is only loudness. The safety never rested on the default -
     * {@code Organisation.status} initialises to PENDING in Java and every write goes through the
     * entity - so removing it converts a silent safe landing into a visible failure, which is the
     * whole of the gain.
     *
     * <p>Asserted as null rather than by string, for the same reason the old version avoided
     * string-equality: the claim is that a forgotten insert gets NOTHING and fails, not how the
     * catalogue renders an absence.
     */
    @Test
    void thereIsNoColumnDefaultSoAnInsertBypassingTheEntityFailsLoudly() {
        String columnDefault = jdbcTemplate.query(
                "select column_default from information_schema.columns "
                        + "where table_name = 'organisations' and column_name = 'status'",
                rs -> rs.next() ? rs.getString(1) : "COLUMN MISSING");

        assertThat(columnDefault)
                .as("the column must still exist - a null here from a MISSING column would look "
                        + "identical to a dropped default, and they are not the same thing")
                .isNotEqualTo("COLUMN MISSING");
        assertThat(columnDefault)
                .as("no default: an insert that omits status - any path bypassing the entity - must "
                        + "fail NOT NULL rather than quietly landing PENDING")
                .isNull();
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
        return securityContext(context);
    }
}

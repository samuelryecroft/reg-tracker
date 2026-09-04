package ninja.samryecroft.returnhome.tracker.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.audit.AuditEvent;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventRepository;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
import ninja.samryecroft.returnhome.tracker.security.LoginAttemptService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T113 Inc 1: the properties of an Entra principal that only show up once it is inside the running
 * application - reaching a controller, reaching the audit listener, and being dereferenced with no
 * transaction open.
 *
 * <p>Every one of these fails <em>silently</em> if the principal is wrong, which is why they are
 * asserted individually rather than inferred from a sign-in that appeared to work.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EntraSignInIntegrationTest extends AbstractIntegrationTest {

    private static final String OBJECT_ID = "b7c1e2a4-55d6-4a8f-9e10-2f3a4b5c6d7e";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private ApplicationEventPublisher events;
    @Autowired
    private LoginAttemptService loginAttemptService;

    private String username;

    @BeforeEach
    void seedALinkedAccount() {
        username = "entra-user-" + System.nanoTime();
        User user = new User();
        user.setUsername(username);
        user.setLastName("Khan");
        user.setFirstName("Nadia");
        user.setRoles(new HashSet<>(Set.of(Role.ORG_ADMIN)));
        user.setOrganisation(seededSupplier());
        user.setEnabled(true);
        user.setIdpSubject(OBJECT_ID);
        userRepository.save(user);
    }

    /**
     * Kevin's (a): the OIDC sign-in audit row has its OWN test, not the form-login one extended.
     *
     * <p>This is the defect the whole increment is shaped around. {@code AuthenticationAuditListener}
     * writes LOGIN_SUCCESS only for a principal that {@code instanceof AppUserPrincipal}; a stock
     * {@code DefaultOidcUser} takes the false branch and nothing throws, nothing logs, and no test
     * that covers only form login goes red. The audit trail would simply stop recording sign-ins
     * through the new front door, on a system whose audit trail is a compliance artefact.
     */
    @Test
    void anEntraSignInWritesItsOwnLoginSuccessAuditRow() {
        long before = loginSuccessRowsFor(username);

        events.publishEvent(new AuthenticationSuccessEvent(entraAuthentication()));

        assertThat(loginSuccessRowsFor(username))
                .as("LOGIN_SUCCESS row for an Entra sign-in")
                .isEqualTo(before + 1);
    }

    /**
     * The other half of the test above, and the reason it is worth having: this shows the hazard is
     * real rather than hypothetical.
     *
     * <p>A stock {@code DefaultOidcUser} - what the OIDC path produces without our custom user
     * service - carries the same authorities and authenticates just as successfully, and writes no
     * audit row at all. No exception, no log line, nothing red. Asserting only that our principal
     * DOES write a row would pass equally in a build that had quietly stopped recording sign-ins;
     * this pins that the difference is the principal type.
     */
    @Test
    void aStockOidcPrincipalWouldHaveWrittenNoAuditRowAtAll() {
        long before = loginSuccessRowsFor(username);

        OidcUser stock = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_ORG_ADMIN")), idToken());
        events.publishEvent(new AuthenticationSuccessEvent(new OAuth2AuthenticationToken(
                stock, List.of(new SimpleGrantedAuthority("ROLE_ORG_ADMIN")), "entra")));

        assertThat(loginSuccessRowsFor(username)).isEqualTo(before);
    }

    /**
     * Kevin's (e): the principal actually resolves into an {@code @AuthenticationPrincipal
     * AppUserPrincipal} parameter. That assignability is what lets 50 such parameters across 18
     * controllers stay untouched, so it is asserted rather than assumed - and the failure it guards
     * is an injected null, not a cast error, so it would surface as an NPE somewhere unrelated.
     */
    @Test
    void theEntraPrincipalResolvesIntoAnAuthenticationPrincipalParameter() throws Exception {
        SecurityContext context = new SecurityContextImpl(entraAuthentication());

        mockMvc.perform(get("/").with(securityContext(context)))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * Kevin's (d): the entity graph on {@code findByIdpSubject} is load-bearing under
     * {@code open-in-view=false}. This dereferences the lazy associations with no transaction open,
     * which is the only condition under which a missing graph shows up - and it would show up in
     * production as a LazyInitializationException on a page, not at sign-in.
     */
    @Test
    void thePrincipalsLazyAssociationsSurviveOutsideATransaction() {
        User loaded = userRepository.findByIdpSubject(OBJECT_ID).orElseThrow();
        AppUserPrincipal principal = new EntraUserPrincipal(loaded, idToken(), null);

        assertThat(principal.getAuthorities()).extracting(Object::toString).contains("ROLE_ORG_ADMIN");
        assertThat(principal.getOrganisationId()).isNotNull();
        assertThat(principal.getOrganisationType()).isNotNull();
    }

    /**
     * Kevin's (c): the form-login lockout counter must not gate single sign-on. Credentials are
     * never presented to us on this path, so a local failure count cannot mean anything about it -
     * and if it did, a burst of wrong-password attempts against a username would lock that person
     * out of SSO, surfacing weeks later as one user mysteriously unable to sign in.
     */
    @Test
    void theFormLoginLockoutDoesNotGateSingleSignOn() {
        for (int attempt = 0; attempt < 20; attempt++) {
            loginAttemptService.recordFailure(username, "203.0.113.7");
        }
        assertThat(loginAttemptService.isLocked(username))
                .as("precondition: the form-login path considers this username locked")
                .isTrue();

        User loaded = userRepository.findByIdpSubject(OBJECT_ID).orElseThrow();

        assertThat(new EntraUserPrincipal(loaded, idToken(), null).isAccountNonLocked()).isTrue();
    }

    private long loginSuccessRowsFor(String actor) {
        return auditEventRepository.findByEventTypeOrderByOccurredAtDesc(AuditEventType.LOGIN_SUCCESS).stream()
                .map(AuditEvent::getActorUsernameAtTime)
                .filter(actor::equals)
                .count();
    }

    private OAuth2AuthenticationToken entraAuthentication() {
        User loaded = userRepository.findByIdpSubject(OBJECT_ID).orElseThrow();
        EntraUserPrincipal principal = new EntraUserPrincipal(loaded, idToken(), null);
        return new OAuth2AuthenticationToken(principal,
                List.of(new SimpleGrantedAuthority("ROLE_ORG_ADMIN")), "entra");
    }

    private OidcIdToken idToken() {
        return OidcIdToken.withTokenValue("stub-token")
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(600))
                .subject("pairwise-subject-value")
                .claim("oid", OBJECT_ID)
                .build();
    }
}

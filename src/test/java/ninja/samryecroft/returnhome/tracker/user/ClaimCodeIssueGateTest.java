package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryService;
import ninja.samryecroft.returnhome.tracker.auth.ClaimCodeService;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/**
 * T197. A claim code is only minted when Entra sign-in is actually on - on <em>both</em> paths that
 * mint one.
 *
 * <p>The argument is the one that gated issue-on-create, and Kevin's finding was that I had applied
 * it to only one of its two paths: <b>with Entra off, a claim code cannot be redeemed by anybody</b>,
 * because there is no OIDC login to arrive holding one. Minting it anyway stores a credential, puts
 * it in the audit trail and gives it somewhere to leak from, in exchange for nothing it can ever do.
 *
 * <p><b>Why this test exists rather than the gate alone.</b> Nothing in the suite rendered the admin
 * form's claim-code section or posted to the reissue endpoint, so the gate would have been a guard
 * that had never been armed - and watching a guard pass on correct code cannot distinguish a working
 * guard from an inert one. Only a case that <em>should</em> fail can, which is what the
 * Entra-off half of this pair is.
 *
 * <p>Both directions, deliberately. Without {@link #aReissueIsIssuedWhenEntraIsOn}, a "gate" that
 * refused unconditionally - the shape a careless merge produces - would pass the other test.
 */
class ClaimCodeIssueGateTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ClaimCodeService claimCodeService = mock(ClaimCodeService.class);
    private final AuditEventPublisher auditEventPublisher = mock(AuditEventPublisher.class);

    private final UserAdminController controller = new UserAdminController(
            mock(UserService.class), userRepository, mock(HomeRepository.class),
            mock(OrganisationRepository.class), mock(AuditHistoryService.class),
            auditEventPublisher, claimCodeService);

    @Test
    void noCodeIsMintedByTheReissuePathWhenEntraIsOff() {
        ReflectionTestUtils.setField(controller, "entraEnabled", false);

        assertThatThrownBy(() -> controller.reissueClaimCode(1L, admin(), new ConcurrentModel()))
                .isInstanceOf(AccessDeniedException.class);

        // The branch assertions, and they are the point: refusing after minting would leave the
        // credential in the database and the event in the audit trail, which is the whole of the
        // harm the gate exists to prevent. An exception alone does not prove it did not happen.
        verify(claimCodeService, never()).issue(any());
        verify(auditEventPublisher, never()).claimCodeIssued(any(), any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void aReissueIsIssuedWhenEntraIsOn() {
        ReflectionTestUtils.setField(controller, "entraEnabled", true);
        User user = plainUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(claimCodeService.issue(user)).thenReturn("ABCDE-FGHJK");

        Model model = new ConcurrentModel();
        assertThat(controller.reissueClaimCode(1L, admin(), model)).isEqualTo("admin/claim-code-issued");

        verify(claimCodeService).issue(user);
        verify(auditEventPublisher).claimCodeIssued(any(), any());
        assertThat(model.getAttribute("claimCode")).isEqualTo("ABCDE-FGHJK");
    }

    private AppUserPrincipal admin() {
        User user = plainUser();
        user.setRoles(Set.of(Role.ADMIN));
        return new AppUserPrincipal(user);
    }

    private User plainUser() {
        User user = new User();
        user.setUsername("platform-admin");
        user.setLastName("Admin");
        user.setRoles(Set.of(Role.ADMIN));
        user.setEnabled(true);
        return user;
    }
}

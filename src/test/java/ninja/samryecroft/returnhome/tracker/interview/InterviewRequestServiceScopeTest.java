package ninja.samryecroft.returnhome.tracker.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T139: the two {@link InterviewRequestService} sites consult the shared scope and honour an empty
 * one.
 *
 * <p>This is a different property from the one {@code SupplierScopeTest} pins, and the distinction
 * is the reason this file exists (Kevin, PR #28). That test proves the <em>decision</em> - that
 * {@code supplierScopeFor} returns empty for a reviewer inside a care provider. This proves the
 * <em>wiring</em>: that this particular call site asks, and does not quietly substitute something
 * unscoped when the answer is empty. Only the second can regress here.
 *
 * <p>The regression it guards is specific rather than hypothetical. {@code .orElseGet(List::of)} is
 * right; {@code .orElseGet(() -> repository.findByStatusOrderByCreatedAtDesc(...))} would compile,
 * read perfectly reasonably, and silently restore the old exposure. An empty-list assertion alone
 * would not catch it either - the unfixed code returned an empty list too, for the wrong reason. So
 * these assert that the repository is never asked, which is the same shape as
 * {@code UserServiceVisibilityTest} from T130 and for the same reason.
 *
 * <p>The access service here is real, not mocked, so the wiring is exercised through to the actual
 * decision rather than to a stub of it.
 */
@ExtendWith(MockitoExtension.class)
class InterviewRequestServiceScopeTest {

    @Mock
    private InterviewRequestRepository interviewRequestRepository;
    @Mock
    private ChildRepository childRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private HomeRepository homeRepository;
    @Mock
    private OrganisationRepository organisationRepository;
    @Mock
    private AuditEventPublisher auditEventPublisher;

    private InterviewRequestService service() {
        return new InterviewRequestService(interviewRequestRepository, childRepository, userRepository,
                homeRepository, new OrganisationAccessService(organisationRepository, userRepository),
                auditEventPublisher);
    }

    @Test
    void listPendingReviewAsksTheScopeAndQueriesNothingWhenItIsEmpty() {
        // REVIEWER is supplier-side by convention only, and /reviewer/** admits it - so a reviewer
        // sitting in a care provider reaches this method. Before T139 the non-ADMIN branch was a
        // bare ternary with no role test at all.
        AppUserPrincipal reviewer = principal(Set.of(Role.REVIEWER), organisation(7L, OrgType.CARE_PROVIDER));

        assertThat(service().listPendingReview(reviewer)).isEmpty();

        verify(interviewRequestRepository, never())
                .findByStatusAndHomeOrganisationSupplierOrganisationId(any(), any());
        // And specifically not the unscoped query the ADMIN branch uses - the fallback a wrong
        // orElseGet would reach for.
        verify(interviewRequestRepository, never()).findByStatusOrderByCreatedAtDesc(any());
    }

    @Test
    void listPendingReviewStillScopesToASupplierSideReviewersOwnOrganisation() {
        AppUserPrincipal reviewer = principal(Set.of(Role.REVIEWER), organisation(7L, OrgType.SUPPLIER));
        InterviewRequest pending = new InterviewRequest();
        when(interviewRequestRepository.findByStatusAndHomeOrganisationSupplierOrganisationId(
                InterviewStatus.REPORT_SUBMITTED, 7L)).thenReturn(List.of(pending));

        assertThat(service().listPendingReview(reviewer)).containsExactly(pending);
    }

    @Test
    void listVisibleAsksTheScopeAndQueriesNothingWhenItIsEmpty() {
        // The sibling site, asserted the same way. It has an end-to-end proof as well, but the
        // wiring assertion is what catches an unscoped fallback rather than a wrong branch.
        AppUserPrincipal coordinator = principal(Set.of(Role.COORDINATOR), organisation(7L, OrgType.CARE_PROVIDER));

        assertThat(service().listVisible(coordinator)).isEmpty();

        verify(interviewRequestRepository, never()).findByHomeOrganisationSupplierOrganisationId(any());
        verify(interviewRequestRepository, never()).findAllDetailed();
    }

    @Test
    void listVisibleStillScopesToASupplierSideCoordinatorsOwnOrganisation() {
        AppUserPrincipal coordinator = principal(Set.of(Role.COORDINATOR), organisation(7L, OrgType.SUPPLIER));
        InterviewRequest theirs = new InterviewRequest();
        when(interviewRequestRepository.findByHomeOrganisationSupplierOrganisationId(7L))
                .thenReturn(List.of(theirs));

        assertThat(service().listVisible(coordinator)).containsExactly(theirs);
    }

    private Organisation organisation(Long id, OrgType type) {
        Organisation organisation = new Organisation();
        ReflectionTestUtils.setField(organisation, "id", id);
        organisation.setType(type);
        return organisation;
    }

    private AppUserPrincipal principal(Set<Role> roles, Organisation organisation) {
        User user = new User();
        user.setUsername("t139-wiring");
        user.setRoles(new HashSet<>(roles));
        user.setOrganisation(organisation);
        return new AppUserPrincipal(user);
    }
}

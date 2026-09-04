package ninja.samryecroft.returnhome.tracker.organisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.document.KeyProvider;
import ninja.samryecroft.returnhome.tracker.document.KeyUnavailableException;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T168(b): ACTIVE is a verified fact, not a claim. These are the properties that make the status
 * column worth having - a status that can be reached without the check is worse than no status,
 * because it is the original incident with a reassurance attached.
 */
class OrganisationLifecycleServiceTest {

    private final OrganisationRepository repository = mock(OrganisationRepository.class);
    private final KeyProvider keyProvider = mock(KeyProvider.class);
    private final AuditEventPublisher auditEvents = mock(AuditEventPublisher.class);
    private final OrganisationLifecycleService service =
            new OrganisationLifecycleService(repository, keyProvider, auditEvents);
    private final AppUserPrincipal principal = mock(AppUserPrincipal.class);

    /**
     * A real Organisation rather than a mock, because these tests are about the STATE TRANSITION -
     * a mock holds no state, so it could not tell an activation that happened from one that did
     * not. The id is set reflectively only because Organisation deliberately has no setId: the
     * database assigns it, and the lifecycle code needs one to derive the key name.
     */
    private Organisation pendingOrganisation() {
        Organisation organisation = new Organisation();
        organisation.setName("Acme Care");
        organisation.setType(OrgType.CARE_PROVIDER);
        ReflectionTestUtils.setField(organisation, "id", 2L);
        when(repository.save(any(Organisation.class))).thenAnswer(i -> i.getArgument(0));
        return organisation;
    }

    @Test
    void activationVerifiesTheKeyRatherThanTakingSomebodysWordForIt() {
        Organisation organisation = pendingOrganisation();
        when(keyProvider.keyExists(anyLong())).thenReturn(true);

        Organisation activated = service.activate(organisation, principal);

        assertThat(activated.getStatus()).isEqualTo(OrgStatus.ACTIVE);
        verify(auditEvents).organisationActivated(eq(activated), any(String.class), eq(principal));
    }

    /**
     * The load-bearing one. Without this, ACTIVE means "an admin clicked a button" and the
     * organisation is free to accept a child record it cannot encrypt.
     */
    @Test
    void anOrganisationWhoseKeyIsAbsentCannotReachActive() {
        Organisation organisation = pendingOrganisation();
        when(keyProvider.keyExists(anyLong())).thenReturn(false);

        assertThatThrownBy(() -> service.activate(organisation, principal))
                .isInstanceOf(OrganisationNotActivatableException.class);

        assertThat(organisation.getStatus()).isEqualTo(OrgStatus.PENDING);
        verify(repository, never()).save(any(Organisation.class));
        verify(auditEvents, never()).organisationActivated(any(), any(), any());
    }

    /**
     * "Unreachable" is not "absent", and collapsing them would refuse an organisation whose key is
     * perfectly fine while the vault happens to be down - turning a transient fault into a
     * permanent-looking onboarding failure. The exception travels as the transient thing it is.
     */
    @Test
    void aVaultOutageIsNotReportedAsAMissingKey() {
        Organisation organisation = pendingOrganisation();
        when(keyProvider.keyExists(anyLong()))
                .thenThrow(new KeyUnavailableException("Key Vault is unreachable"));

        assertThatThrownBy(() -> service.activate(organisation, principal))
                .isInstanceOf(KeyUnavailableException.class)
                .isNotInstanceOf(OrganisationNotActivatableException.class);

        assertThat(organisation.getStatus()).isEqualTo(OrgStatus.PENDING);
    }

    /**
     * Restore goes to PENDING, not ACTIVE: an organisation archived long enough for its key to have
     * been rotated away must not slip back into use on the strength of having once been active. It
     * passes the same gate as any other activation.
     */
    @Test
    void restoringAnArchivedOrganisationSendsItBackThroughTheKeyCheck() {
        Organisation organisation = pendingOrganisation();
        service.archive(organisation, "archived", principal);
        assertThat(organisation.getStatus()).isEqualTo(OrgStatus.ARCHIVED);

        service.restoreToPending(organisation, principal);

        assertThat(organisation.getStatus()).isEqualTo(OrgStatus.PENDING);
        verify(keyProvider, never()).keyExists(anyLong());
    }

    /** The archived/removed distinction lives on the event, because the state has one value for both. */
    @Test
    void archivingRecordsWhatTheHumanMeantEvenThoughTheStateCannotDistinguishIt() {
        Organisation archived = pendingOrganisation();
        Organisation removed = pendingOrganisation();

        service.archive(archived, "archived", principal);
        service.archive(removed, "removed", principal);

        assertThat(archived.getStatus()).isEqualTo(removed.getStatus());
        verify(auditEvents).organisationArchived(eq(archived), eq("archived"), eq(principal));
        verify(auditEvents).organisationArchived(eq(removed), eq("removed"), eq(principal));
    }
}

package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Set;
import ninja.samryecroft.returnhome.tracker.export.ExportPurpose;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * T379: the two disclosure events are written through the repository, not published, and a
 * reference long enough to reach the metadata cut loses itself and never the checksum.
 */
class AuditEventPublisherDisclosureTest {

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AuditEventRepository repository = mock(AuditEventRepository.class);
    private final AuditEventPublisher publisher =
            new AuditEventPublisher(events, mock(UserRepository.class), repository);

    private static AppUserPrincipal anExporter() {
        Organisation org = new Organisation();
        org.setName("Provider");
        User user = new User();
        user.setUsername("exporter");
        user.setEmail("exporter@example.test");
        user.setRoles(Set.of(Role.ORG_ADMIN));
        user.setOrganisation(org);
        user.setEnabled(true);
        return new AppUserPrincipal(user, false);
    }

    @Test
    void aCaseFileExportIsWrittenNotPublishedAndKeepsItsChecksumWhateverTheReferenceDoes() {
        publisher.caseFileExported(7L, 3L, ExportPurpose.values()[0], "r".repeat(1500), "all",
                4, 1, 2, true, "deadbeef", anExporter());

        ArgumentCaptor<AuditEvent> written = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(written.capture());
        verify(events, never()).publishEvent(any());
        String metadata = written.getValue().getMetadata();
        assertThat(metadata).hasSizeLessThanOrEqualTo(1000);
        assertThat(metadata)
                .as("everything that identifies the disclosure survives; only the reference is cut")
                .contains("checksum=deadbeef")
                .contains("included=4")
                .contains("documents=2")
                .endsWith("...");
        assertThat(metadata.indexOf("reference=")).isGreaterThan(metadata.indexOf("checksum="));
    }

    @Test
    void anAuditTrailExportIsWrittenNotPublished() {
        publisher.auditQueryExported(3L, ExportPurpose.values()[0], "ref", "all · 12 rows", 12,
                "cafebabe", anExporter());

        ArgumentCaptor<AuditEvent> written = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(written.capture());
        verify(events, never()).publishEvent(any());
        assertThat(written.getValue().getMetadata()).contains("checksum=cafebabe").endsWith("reference=ref");
    }
}

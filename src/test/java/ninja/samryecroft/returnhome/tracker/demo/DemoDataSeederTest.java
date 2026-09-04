package ninja.samryecroft.returnhome.tracker.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestService;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.report.ReportService;
import ninja.samryecroft.returnhome.tracker.theme.ThemeSettingsRepository;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The demo seed writes fictional children, homes and users. What matters about it is therefore not
 * what it creates but <em>when it refuses to</em>: it must be unreachable outside the demo profile,
 * and it must not fire a second time against a database it has already claimed.
 *
 * <p>Driven without a full Boot context on purpose. The Testcontainers database is shared across
 * test classes, so actually running the seeder here would leave a whole demo tenancy behind for
 * every later test to trip over.
 */
class DemoDataSeederTest {

    // --- the profile gate: the only thing keeping this out of a real deployment ---

    @Test
    void isNotRegisteredWithoutTheDemoProfile() {
        contextRunner().run(context -> assertThat(context).doesNotHaveBean(DemoDataSeeder.class));
    }

    @Test
    void isRegisteredUnderTheDemoProfile() {
        contextRunner()
                .withPropertyValues("spring.profiles.active=demo")
                .run(context -> assertThat(context).hasSingleBean(DemoDataSeeder.class));
    }

    /**
     * Registers the seeder as an annotated class rather than from a {@code @Bean} method, because
     * {@code @Profile} on a class is only evaluated on the former - a {@code @Bean} method would
     * hand back an instance regardless of profile and quietly make this test prove nothing.
     */
    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(Collaborators.class, DemoDataSeeder.class);
    }

    // --- the idempotency guard ---

    @Test
    void recognisesADatabaseItHasAlreadySeeded() {
        Mocks mocks = new Mocks();
        mocks.suppliersInDatabase(supplier(DemoDataSeeder.MARKER_ORGANISATION));

        assertThat(mocks.seeder().alreadySeeded()).isTrue();
    }

    @Test
    void treatsAnEmptyDatabaseAsUnseeded() {
        Mocks mocks = new Mocks();
        mocks.suppliersInDatabase();

        assertThat(mocks.seeder().alreadySeeded()).isFalse();
    }

    /** Someone else's supplier organisation is not our marker, and must not be read as one. */
    @Test
    void treatsAnUnrelatedSupplierAsUnseeded() {
        Mocks mocks = new Mocks();
        mocks.suppliersInDatabase(supplier("Some Other Supplier Ltd"));

        assertThat(mocks.seeder().alreadySeeded()).isFalse();
    }

    /** A second boot against a seeded database must touch nothing else at all. */
    @Test
    void writesNothingWhenAlreadySeeded() {
        Mocks mocks = new Mocks();
        mocks.suppliersInDatabase(supplier(DemoDataSeeder.MARKER_ORGANISATION));

        mocks.seeder().run(null);

        verifyNoInteractions(mocks.homes, mocks.children, mocks.users, mocks.requests,
                mocks.reports, mocks.themes, mocks.reportService, mocks.audit,
                mocks.passwordEncoder);
        Mockito.verify(mocks.organisations, Mockito.never()).save(Mockito.any());
    }

    private static Organisation supplier(String name) {
        Organisation organisation = new Organisation();
        organisation.setName(name);
        organisation.setType(OrgType.SUPPLIER);
        return organisation;
    }

    /** The seeder's collaborators, so the context tests exercise the profile gate and nothing else. */
    @Configuration(proxyBeanMethods = false)
    static class Collaborators {
        @Bean OrganisationRepository organisationRepository() { return mock(OrganisationRepository.class); }
        @Bean ThemeSettingsRepository themeSettingsRepository() { return mock(ThemeSettingsRepository.class); }
        @Bean HomeRepository homeRepository() { return mock(HomeRepository.class); }
        @Bean ChildRepository childRepository() { return mock(ChildRepository.class); }
        @Bean UserRepository userRepository() { return mock(UserRepository.class); }
        @Bean InterviewRequestRepository interviewRequestRepository() { return mock(InterviewRequestRepository.class); }
        @Bean InterviewReportRepository interviewReportRepository() { return mock(InterviewReportRepository.class); }
        @Bean InterviewRequestService interviewRequestService() { return mock(InterviewRequestService.class); }
        @Bean ReportService reportService() { return mock(ReportService.class); }
        @Bean AuditEventPublisher auditEventPublisher() { return mock(AuditEventPublisher.class); }
        @Bean PasswordEncoder passwordEncoder() { return mock(PasswordEncoder.class); }
        @Bean DemoProperties demoProperties() { return new DemoProperties(); }
    }

    private static final class Mocks {
        final OrganisationRepository organisations = mock(OrganisationRepository.class);
        final ThemeSettingsRepository themes = mock(ThemeSettingsRepository.class);
        final HomeRepository homes = mock(HomeRepository.class);
        final ChildRepository children = mock(ChildRepository.class);
        final UserRepository users = mock(UserRepository.class);
        final InterviewRequestRepository requests = mock(InterviewRequestRepository.class);
        final InterviewReportRepository reports = mock(InterviewReportRepository.class);
        final InterviewRequestService interviewRequestService = mock(InterviewRequestService.class);
        final ReportService reportService = mock(ReportService.class);
        final AuditEventPublisher audit = mock(AuditEventPublisher.class);
        final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

        void suppliersInDatabase(Organisation... suppliers) {
            Mockito.when(organisations.findByTypeOrderByName(OrgType.SUPPLIER))
                    .thenReturn(List.of(suppliers));
        }

        DemoDataSeeder seeder() {
            return new DemoDataSeeder(organisations, themes, homes, children, users, requests,
                    reports, interviewRequestService, reportService, audit, passwordEncoder,
                    new DemoProperties());
        }
    }
}

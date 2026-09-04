package ninja.samryecroft.returnhome.tracker.organisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The scope returned by {@code homeScopeFor} answers "no" for an unsaved Home rather than throwing.
 *
 * <p>{@code canView} already guarded {@code home == null}, then handed {@code home.getId()} to a
 * {@code Set.copyOf(...)} - and the JDK's immutable sets throw {@link NullPointerException} on
 * {@code contains(null)} instead of answering false. Nothing reaches it with an unsaved Home today,
 * so this is not a live defect; it is the null guard on the line above being made to mean what it
 * looks like it means. An access check that throws is worse than one that denies, because the caller
 * sees a 500 where it asked a yes/no question.
 */
@ExtendWith(MockitoExtension.class)
class HomeScopeNullSafetyTest {

    @Mock
    private OrganisationRepository organisationRepository;
    @Mock
    private UserRepository userRepository;

    @Test
    void anUnsavedHomeIsDeniedRatherThanThrowing() {
        when(userRepository.findHomeIds(any())).thenReturn(List.of(1L, 2L));
        OrganisationAccessService service = new OrganisationAccessService(organisationRepository, userRepository);
        HomeScope scope = service.homeScopeFor(principal());

        // Restoring the old guard makes this throw NPE from Set.copyOf(...).contains(null).
        assertThatCode(() -> scope.canView(new Home())).doesNotThrowAnyException();
        assertThat(scope.canView(new Home())).isFalse();
    }

    @Test
    void andSoIsANullHome() {
        when(userRepository.findHomeIds(any())).thenReturn(List.of());
        OrganisationAccessService service = new OrganisationAccessService(organisationRepository, userRepository);

        assertThat(service.homeScopeFor(principal()).canView(null)).isFalse();
    }

    private AppUserPrincipal principal() {
        User user = new User();
        user.setUsername("scope-null-safety");
        user.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        return new AppUserPrincipal(user);
    }
}

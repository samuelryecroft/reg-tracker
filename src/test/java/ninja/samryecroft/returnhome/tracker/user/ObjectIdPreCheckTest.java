package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.user.dto.CreateUserForm;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * T113 Inc 2: the duplicate <em>pre-check</em> specifically, separated from the unique constraint
 * that sits behind it.
 *
 * <p>The form-level test cannot tell the two apart - remove the pre-check and it still passes,
 * because {@code uq_users_idp_subject} refuses the insert and the controller translates it either
 * way. By our own T145 reasoning an untested layer indistinguishable from the one beneath it is
 * decoration, so the property it actually adds is asserted here instead: <b>the save never happens
 * at all.</b>
 *
 * <p>That is the difference worth keeping. Letting the constraint be the ordinary path means the
 * violation surfaces at flush, after everything else in the transaction has already been written
 * and has to be rolled back. Without this test someone could delete the pre-check, watch every
 * other test pass, and silently turn the common case into that.
 */
class ObjectIdPreCheckTest {

    private static final String OBJECT_ID = "6f0a1c9e-3c2b-4c1a-9f77-0c0a1b2c3d4e";

    private final UserRepository userRepository = mock(UserRepository.class);

    private final UserService userService = new UserService(userRepository,
            mock(HomeRepository.class), mock(OrganisationRepository.class),
            mock(OrganisationAccessService.class), mock(PasswordEncoder.class),
            mock(AuditEventPublisher.class), new RoleMatrix());

    @Test
    void anObjectIdAlreadyHeldByAnotherAccountIsRefusedBeforeAnythingIsSaved() {
        // Mocked rather than built: User has no setId, and the pre-check compares owners by id -
        // a real instance would have a null id and look like the account being edited.
        User existingHolder = mock(User.class);
        when(existingHolder.getId()).thenReturn(41L);
        when(userRepository.findByIdpSubject(OBJECT_ID)).thenReturn(Optional.of(existingHolder));

        assertThatThrownBy(() -> userService.create(formWith(OBJECT_ID), adminPrincipal()))
                .isInstanceOf(DuplicateObjectIdException.class);

        // The assertion the form-level test cannot make: the constraint never had to fire, so no
        // partially-written transaction had to be rolled back.
        verify(userRepository, never()).save(any());
    }

    /**
     * The paired positive. Without it, a "pre-check" that rejected every object id would satisfy the
     * test above - and the failure would be indistinguishable from the duplicate case it is meant to
     * catch.
     */
    @Test
    void anUnusedObjectIdIsSaved() {
        when(userRepository.findByIdpSubject(any())).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        userService.create(formWith(OBJECT_ID), adminPrincipal());

        verify(userRepository).save(any());
    }

    private CreateUserForm formWith(String objectId) {
        CreateUserForm form = new CreateUserForm();
        form.setUsername("new-account");
        form.setFirstName("Nadia");
        form.setLastName("Khan");
        form.setEmail("nadia@example.test");
        form.setIdpSubject(objectId);
        form.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        return form;
    }

    private AppUserPrincipal adminPrincipal() {
        User admin = new User();
        admin.setUsername("platform-admin");
        admin.setRoles(Set.of(Role.ADMIN));
        admin.setEnabled(true);
        return new AppUserPrincipal(admin);
    }
}

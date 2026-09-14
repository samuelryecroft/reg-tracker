package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * T344: the sign-in lookup must return a user whose collections can still be read after the session
 * that loaded it has closed.
 *
 * <p><b>Why this test exists rather than a comment on the repository.</b> Moving sign-in from
 * username to email replaced {@code findByUsername} - which has always carried an entity graph -
 * with {@code findByEmailIgnoreCase}, which was first written without one. {@code homes},
 * {@code organisation} and {@code roles} are LAZY and {@code spring.jpa.open-in-view=false}, so the
 * lookup was <em>correct about which row</em> and wrong about how much of it, and every
 * authenticated request that reached a home threw {@code LazyInitializationException}.
 *
 * <p><b>It surfaced as 177 failures in tests about other things</b> - reports, exports, dashboards -
 * and as six UI tests timing out waiting for a button on a page that was actually a 500. Nothing
 * pointed at the lookup. A defect whose signal appears everywhere except at its cause is one that
 * gets fixed by whoever is nearest, so this asserts it where it lives.
 *
 * <p>The second test is the POSITIVE CONTROL. A test that only ever sees collections load cannot
 * tell "the graph is there" from "these collections happen not to be lazy", and would pass just as
 * happily against a build where the whole problem is impossible. It proves the detector works by
 * showing the ungraphed read failing in the same conditions.
 */
@SpringBootTest
class TheSignInLookupReturnsAUsableUserNotALazyOneTest extends AbstractIntegrationTest {

    @Autowired private AppUserDetailsService appUserDetailsService;
    @Autowired private UserRepository userRepository;
    @Autowired private HomeRepository homeRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User staffWithAHome() {
        Organisation careProvider = seededCareProvider();
        Home home = new Home();
        home.setName("Lazy Load House");
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        User user = new User();
        user.setUsername("lazy-probe");
        user.setEmail("lazy-probe@example.test");
        user.setFirstName("Lazy");
        user.setLastName("Probe");
        user.setPassword(passwordEncoder.encode("correct-horse-battery-staple"));
        user.setRoles(Set.of(Role.HOME_STAFF));
        user.setHomes(Set.of(home));
        user.setEnabled(true);
        return userRepository.save(user);
    }

    @Test
    void signingInByEmailLoadsTheHomesAndRolesTheRequestWillGoOnToRead() {
        User saved = staffWithAHome();

        // No transaction here on purpose: this is the state a real request is in by the time it
        // reads the principal.
        UserDetails details = appUserDetailsService.loadUserByUsername(saved.getEmail());
        User loaded = ((AppUserPrincipal) details).getUser();

        assertThat(loaded.getHomes())
                .as("the authorisation checks read homes, so the lookup has to have fetched them")
                .hasSize(1);
        assertThat(loaded.getRoles()).containsExactly(Role.HOME_STAFF);
        assertThat(details.getAuthorities()).isNotEmpty();
    }

    /**
     * THE POSITIVE CONTROL: the same read, without the entity graph, in the same conditions.
     *
     * <p>If this ever stops throwing, the test above has stopped being evidence of anything and the
     * mistake is to trust it, not to delete this.
     */
    @Test
    void andWithoutTheGraphThatSameReadFails_soTheTestAboveIsProvingSomething() {
        User saved = staffWithAHome();

        User ungraphed = userRepository.findById(saved.getId()).orElseThrow();

        assertThatThrownBy(() -> ungraphed.getHomes().size())
                .as("a plain findById outside a transaction cannot reach a LAZY collection - which "
                        + "is exactly what the sign-in lookup was doing")
                .isInstanceOf(LazyInitializationException.class);
    }

    /** The emergency account signs in by name down a different repository method - same requirement. */
    @Test
    void theBreakGlassLookupIsFetchedTheSameWay() {
        User emergency = userRepository.findByBreakGlassTrue().orElseThrow();

        assertThat(emergency.getRoles())
                .as("the way back in must not throw on first use of its own authorities")
                .isNotEmpty();
        assertThat(emergency.getHomes()).isNotNull();
    }
}

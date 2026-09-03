package ninja.samryecroft.returnhome.tracker.interview;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A full application context rather than a {@code @DataJpaTest} slice, and that is a consequence of
 * field encryption rather than a preference. The narrative columns on InterviewRequest and the
 * names on Child are ciphertext now, written by a Hibernate insert listener that is registered when
 * the application's own Hibernate configuration is in play. A slice builds its SessionFactory
 * without it, so rows would be written with null ciphertext and read back undecryptable - the test
 * would be exercising an entity that does not exist in production.
 */
@SpringBootTest
class InterviewRequestRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private InterviewRequestRepository interviewRequestRepository;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    void scopesRequestsByHomeAndByAllocatedVisitor() {
        Organisation careProviderOrg = seededCareProvider();
        Home homeA = homeRepository.save(newHome("Home A", careProviderOrg));
        Home homeB = homeRepository.save(newHome("Home B", careProviderOrg));

        Child childA = childRepository.save(newChild("Alex", "Smith", homeA));
        Child childB = childRepository.save(newChild("Jordan", "Lee", homeB));

        User staffA = userRepository.save(newUser("staffA", Role.HOME_STAFF, homeA));
        User visitor = userRepository.save(newUser("visitor1", Role.VISITOR, null));

        InterviewRequest requestA = new InterviewRequest();
        requestA.setChild(childA);
        requestA.setHome(homeA);
        requestA.setRequestedBy(staffA);
        requestA.setStatus(InterviewStatus.SCHEDULED);
        requestA.setReturnedAt(LocalDateTime.now().minusHours(4));
        requestA.setAllocatedVisitor(visitor);
        interviewRequestRepository.save(requestA);

        InterviewRequest requestB = new InterviewRequest();
        requestB.setChild(childB);
        requestB.setHome(homeB);
        requestB.setRequestedBy(staffA);
        requestB.setStatus(InterviewStatus.REQUESTED);
        requestB.setReturnedAt(LocalDateTime.now().minusHours(4));
        interviewRequestRepository.save(requestB);

        assertThat(interviewRequestRepository.findByHomeId(homeA.getId()))
                .extracting(r -> r.getChild().getFullName())
                .containsExactly("Alex Smith");

        assertThat(interviewRequestRepository.findByHomeId(homeB.getId()))
                .extracting(r -> r.getChild().getFullName())
                .containsExactly("Jordan Lee");

        assertThat(interviewRequestRepository.findByAllocatedVisitorId(visitor.getId()))
                .extracting(r -> r.getChild().getFullName())
                .containsExactly("Alex Smith");

        assertThat(interviewRequestRepository.findAllDetailed()).hasSize(2);
    }

    private Home newHome(String name, Organisation organisation) {
        Home home = new Home();
        home.setName(name);
        home.setOrganisation(organisation);
        return home;
    }

    private Child newChild(String firstName, String lastName, Home home) {
        Child child = new Child();
        child.setFirstName(firstName);
        child.setLastName(lastName);
        child.setDateOfBirth(LocalDate.of(2010, 1, 1));
        child.setHome(home);
        return child;
    }

    private User newUser(String username, Role role, Home home) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("irrelevant");
        user.setFullName(username);
        user.setRoles(Set.of(role));
        user.setHome(home);
        return user;
    }
}

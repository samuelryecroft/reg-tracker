package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * T264/T322: an address may belong to only one account, because it is where sign-in codes go.
 *
 * <p>T264 measured the live database: <b>7 users, three of them (ids 4, 5, 7) sharing one address.</b>
 * With a second factor switched on, those three accounts are mutually reachable - whoever reads that
 * mailbox can complete any of the three sign-ins - and two of the three cannot receive a code that is
 * only theirs. <b>That is not a data-cleanliness problem, it is three accounts with one credential.</b>
 */
@SpringBootTest
class AnAddressBelongsToOneAccountTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User save(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setFirstName("Test");
        user.setLastName("User");
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("correct-horse-battery-staple"));
        user.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        user.setEnabled(true);
        return userRepository.saveAndFlush(user);
    }

    @Test
    void twoAccountsCannotShareAnAddress() {
        String suffix = System.nanoTime() + "@example.test";
        save("dup-a-" + System.nanoTime(), "shared-" + suffix);

        assertThatThrownBy(() -> save("dup-b-" + System.nanoTime(), "shared-" + suffix))
                .as("the database must refuse a second account on one address")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * THE ONE MOST LIKELY TO BE MISSED. The constraint binds the MAILBOX, not the string.
     *
     * <p>Two rows differing only in case are two different strings and one destination. A plain
     * {@code UNIQUE} would admit them, and the two accounts would then receive each other's sign-in
     * codes in one inbox - the exact defect the index exists to prevent, waved through on a
     * technicality.
     */
    @Test
    void addressesDifferingOnlyInCaseAreTheSameAddress() {
        long n = System.nanoTime();
        save("case-a-" + n, "Case.Test." + n + "@Example.test");

        assertThatThrownBy(() -> save("case-b-" + n, "case.test." + n + "@example.test"))
                .as("one mailbox, so one account - case must not create a second identity")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * A null address is still allowed, and this is deliberate rather than an omission.
     *
     * <p>The card asked for {@code UNIQUE + NOT NULL}. NOT NULL was ruled out: the property needed is
     * "no two accounts share a factor destination", not "every row has an address" - and the bootstrap
     * admin (id 1 in the measured database) legitimately has none, because
     * {@code SecondFactorPolicy} exempts it so a mail outage cannot lock out whoever would restore
     * mail. An account with no address is already refused at sign-in by
     * {@code SecondFactorService.canChallenge}, which is the layer that knows whether this account
     * needs a factor at all.
     */
    @Test
    void severalAccountsMayHaveNoAddressAtAll() {
        long n = System.nanoTime();
        save("null-a-" + n, null);
        save("null-b-" + n, null);

        assertThat(userRepository.findByUsername("null-b-" + n))
                .as("a partial index must not collapse two accounts that have no address")
                .isPresent();
    }
}

package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * T380 (CODE-REVIEW-2026-09-18 P1.4): the attempt cap on a challenge holds under concurrency.
 *
 * <p>The scenario the review states: an attacker holding the password fires many concurrent codes
 * at one challenge. {@code verify} read the attempt count, incremented it and wrote it back, so
 * concurrent verifications each saw the same count and the cap "never burned". With the row
 * locked for the verification, the outcomes are exactly what a serial run would produce: four
 * wrong codes, then the one that burns the challenge, then only refusals - and the stored count
 * is the cap, not something below it.
 */
@SpringBootTest
class SecondFactorAttemptCapUnderConcurrencyTest extends AbstractIntegrationTest {

    private static final int CONCURRENT_GUESSES = 40;

    @Autowired
    private SecondFactorService secondFactorService;
    @Autowired
    private LoginChallengeRepository challenges;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AppProperties appProperties;

    @Test
    void manySimultaneousWrongCodesBurnTheChallengeExactlyAtTheCap() throws Exception {
        int cap = appProperties.getSecurity().getSecondFactor().getMaxAttempts();
        String suffix = "-" + System.nanoTime();
        User user = new User();
        user.setUsername("t380" + suffix);
        user.setEmail("t380" + suffix + "@example.test");
        user.setLastName("Concurrent");
        user.setRoles(new HashSet<>(Set.of(Role.VIEWER)));
        user.setEnabled(true);
        user = userRepository.saveAndFlush(user);
        LoginChallenge challenge = challenges.save(new LoginChallenge(user.getId(),
                passwordEncoder.encode("123456"), Instant.now().plus(10, ChronoUnit.MINUTES),
                ChallengePurpose.SIGN_IN));

        final User target = user;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<SecondFactorService.Outcome>> results = new ArrayList<>();
        try {
            for (int i = 0; i < CONCURRENT_GUESSES; i++) {
                Callable<SecondFactorService.Outcome> guess = () -> {
                    start.await();
                    return secondFactorService.verify(target, "000000");
                };
                results.add(pool.submit(guess));
            }
            start.countDown();
            List<SecondFactorService.Outcome> outcomes = new ArrayList<>();
            for (Future<SecondFactorService.Outcome> result : results) {
                outcomes.add(result.get());
            }

            assertThat(challenges.findById(challenge.getId()).orElseThrow().getAttempts())
                    .as("the stored attempt count is the cap - every guess before it was counted, "
                            + "none after it")
                    .isEqualTo(cap);
            assertThat(outcomes.stream().collect(Collectors.groupingBy(o -> o, Collectors.counting())))
                    .as("exactly cap-1 plain refusals, exactly one burn, and the rest turned away")
                    .containsEntry(SecondFactorService.Outcome.WRONG_CODE, (long) cap - 1)
                    .containsEntry(SecondFactorService.Outcome.BURNED, 1L)
                    .containsEntry(SecondFactorService.Outcome.EXPIRED, (long) CONCURRENT_GUESSES - cap);
        } finally {
            pool.shutdownNow();
        }
    }
}

package ninja.samryecroft.returnhome.tracker.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * T218: the four ways a download link can fail must be indistinguishable.
 *
 * <p>{@link ExportLinkService#redeem} answers {@code Optional.empty()} when the token is unknown,
 * expired, already spent, or another user's. <b>Distinguishing them would be an enumeration
 * oracle</b>: telling someone holding a guessed token that it is "expired" rather than "unknown"
 * tells them it was once real, which is most of the work of finding a live one.
 *
 * <p><b>This asserts the collapse at its source rather than at the page.</b> The template renders
 * whatever the controller reaches it with, and the controller cannot tell these apart because they
 * arrive already collapsed - so the property lives here, one layer below the copy. A test that only
 * compared two rendered pages would pass just as well on a service that had begun distinguishing
 * them and a controller that happened not to look.
 *
 * <p>The fourth case, another user's token, is the one most easily lost: it is the only one where a
 * real, live, unexpired pack exists and is deliberately withheld, so it is the one a future
 * "helpful" branch would most plausibly single out.
 */
class ExportLinkIndistinguishabilityTest {

    private static final Long OWNER = 7L;
    private static final Long SOMEONE_ELSE = 8L;

    private final ExportLinkService service = new ExportLinkService();

    private static ExportPack pack() {
        return new ExportPack("case-file.zip", new byte[] {1, 2, 3}, "sha", null);
    }

    /**
     * Replaces the held entry with an equivalent one that has already expired.
     *
     * <p>Held is a record, so its components cannot be reassigned - the first version of this tried
     * and failed. Rebuilding through the canonical constructor is the honest way to age an entry,
     * and it beats the alternatives: waiting out the real lifetime would make this test slow and
     * timing-dependent, and widening the service's API to allow injecting a clock would change
     * production code to suit a test.
     */
    @SuppressWarnings("unchecked")
    private void expire(String token) throws ReflectiveOperationException {
        Field heldField = ExportLinkService.class.getDeclaredField("held");
        heldField.setAccessible(true);
        Map<String, Object> held = (Map<String, Object>) heldField.get(service);
        Object entry = held.get(token);
        assertThat(entry).as("the token must be held before it can be aged").isNotNull();

        Class<?> heldType = entry.getClass();
        var constructor = heldType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);   // Held is a PRIVATE record inside the service.
        Object expired = constructor.newInstance(
                readComponent(entry, "pack"),
                readComponent(entry, "ownerUserId"),
                Instant.now().minus(Duration.ofSeconds(1)));
        held.put(token, expired);
    }

    private static Object readComponent(Object record, String name) throws ReflectiveOperationException {
        var accessor = record.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(record);
    }

    @Test
    void allFourFailureModesAnswerTheSameEmptyOptional() throws ReflectiveOperationException {
        Optional<ExportPack> unknown = service.redeem("never-issued", OWNER);

        String spent = service.hold(pack(), OWNER);
        service.redeem(spent, OWNER);
        Optional<ExportPack> alreadyUsed = service.redeem(spent, OWNER);

        String expired = service.hold(pack(), OWNER);
        expire(expired);
        Optional<ExportPack> hasExpired = service.redeem(expired, OWNER);

        String someoneElses = service.hold(pack(), OWNER);
        Optional<ExportPack> wrongUser = service.redeem(someoneElses, SOMEONE_ELSE);

        assertThat(unknown).as("an unknown token").isEmpty();
        assertThat(alreadyUsed).as("a token already spent").isEmpty();
        assertThat(hasExpired).as("an expired token").isEmpty();
        assertThat(wrongUser)
                .as("another user's live token - the case where a real, unexpired pack DOES exist "
                        + "and is withheld, and therefore the one a later 'helpful' branch would "
                        + "most plausibly single out")
                .isEmpty();

        // Said as one assertion too, because the property is that they are the SAME answer, not
        // that each is empty for its own reasons.
        assertThat(Map.of("unknown", unknown, "used", alreadyUsed,
                        "expired", hasExpired, "wrongUser", wrongUser).values())
                .as("all four must be the same answer - the moment one carries a distinguishing "
                        + "value, the page above it can leak which one it was, whatever its copy says")
                .containsOnly(Optional.empty());
    }

    @Test
    void redeemingAnotherUsersTokenStillSpendsIt() throws ReflectiveOperationException {
        String token = service.hold(pack(), OWNER);

        assertThat(service.redeem(token, SOMEONE_ELSE)).isEmpty();

        // Consumed on the first look regardless of who looked, so a leaked link cannot be probed
        // by the wrong user and then used by the right one. The owner gets the same empty answer.
        assertThat(service.redeem(token, OWNER))
                .as("the token is spent by the failed attempt, not only by a successful one")
                .isEmpty();
    }
}

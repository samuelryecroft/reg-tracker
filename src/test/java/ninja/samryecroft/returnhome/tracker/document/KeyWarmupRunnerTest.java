package ninja.samryecroft.returnhome.tracker.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T181: what startup warmup may and may not do.
 *
 * <p>The performance claim itself is not testable here and this file does not pretend to test it -
 * proving the cold-start cliff is gone needs App Insights against a real deployment, which Pam
 * holds. What is testable, and is what could go wrong in a way no dashboard would show, is the
 * <em>shape</em> of the warmup: that it never creates a key, never masks an absent one, and can
 * never stop the application starting.
 */
class KeyWarmupRunnerTest {

    private final KeyProvider keyProvider = mock(KeyProvider.class);
    private final OrganisationRepository organisationRepository = mock(OrganisationRepository.class);

    private final com.azure.core.credential.TokenCredential credential =
            mock(com.azure.core.credential.TokenCredential.class);

    private KeyWarmupRunner runner() {
        return new KeyWarmupRunner(keyProvider, organisationRepository, Duration.ofSeconds(30),
                credential, "https://vault.azure.net/.default");
    }

    private Organisation careProvider(long id, boolean active) {
        Organisation organisation = new Organisation();
        organisation.setName("Org " + id);
        organisation.setType(OrgType.CARE_PROVIDER);
        ReflectionTestUtils.setField(organisation, "id", id);
        if (active) {
            ReflectionTestUtils.setField(organisation, "status",
                    ninja.samryecroft.returnhome.tracker.organisation.OrgStatus.ACTIVE);
        }
        return organisation;
    }

    /**
     * The whole point: the handle is fetched at startup, so the first request finds it cached.
     * {@code keyExists} is asserted as well as {@code currentKeyFor} because the order is what makes
     * the next test's guarantee hold.
     */
    @Test
    void anActiveOrganisationsKeyHandleIsFetchedDuringStartup() {
        when(organisationRepository.findByTypeOrderByName(OrgType.CARE_PROVIDER))
                .thenReturn(List.of(careProvider(7L, true)));
        when(keyProvider.keyExists(7L)).thenReturn(true);

        runner().run(null);

        verify(keyProvider).keyExists(7L);
        verify(keyProvider).currentKeyFor(7L);
    }

    /**
     * The token is fetched BEFORE anything asks the vault for a key, and that ordering is the fix.
     *
     * <p>Measured on the live deployment: the first {@code getKey} took 32-42 seconds and returned
     * 401 - Key Vault's standard auth challenge, not a refusal - while the SDK acquired a cold
     * managed-identity token inline at ~10 seconds and then retried in ~200ms. So the ordering is
     * the whole behaviour: asserted with an {@code InOrder} rather than by checking the token was
     * fetched at all, because fetching it afterwards would satisfy a call-count assertion and change
     * nothing about the cold start.
     */
    @Test
    void theVaultTokenIsAcquiredBeforeTheFirstKeyLookup() {
        when(organisationRepository.findByTypeOrderByName(OrgType.CARE_PROVIDER))
                .thenReturn(List.of(careProvider(20L, true)));
        when(keyProvider.keyExists(20L)).thenReturn(true);

        runner().run(null);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(credential, keyProvider);
        inOrder.verify(credential).getTokenSync(org.mockito.ArgumentMatchers.any());
        inOrder.verify(keyProvider).keyExists(20L);
    }

    /**
     * Pre-acquisition is an optimisation inside an optimisation, so a failure to get the token must
     * leave the behaviour exactly as it was: the first real call acquires it inline, as it always
     * did. It must never be able to stop the warmup, let alone the application.
     */
    @Test
    void aFailedTokenPreAcquisitionDoesNotStopTheWarmup() {
        when(organisationRepository.findByTypeOrderByName(OrgType.CARE_PROVIDER))
                .thenReturn(List.of(careProvider(21L, true)));
        when(keyProvider.keyExists(21L)).thenReturn(true);
        when(credential.getTokenSync(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("no managed identity here"));

        assertThatCode(() -> runner().run(null)).doesNotThrowAnyException();
        verify(keyProvider).currentKeyFor(21L);
    }

    /**
     * The constraint that matters more than the speed: warmup must not make an unprovisioned
     * organisation look provisioned.
     *
     * <p>{@code currentKeyFor} creates the key on a miss when auto-creation is enabled, so calling
     * it directly would have quietly provisioned every organisation that T168(b)'s activation gate
     * exists to catch. Asking {@code keyExists} first - a read with no creation path - is what keeps
     * an absent key absent, and its first real record failing closed.
     */
    @Test
    void anOrganisationWithNoKeyIsSkippedAndNoKeyIsCreated() {
        when(organisationRepository.findByTypeOrderByName(OrgType.CARE_PROVIDER))
                .thenReturn(List.of(careProvider(8L, true)));
        when(keyProvider.keyExists(8L)).thenReturn(false);

        runner().run(null);

        verify(keyProvider).keyExists(8L);
        verify(keyProvider, never()).currentKeyFor(anyLong());
    }

    /**
     * A PENDING organisation has not passed the activation gate, so by construction it has no KEK.
     * Warming it would be a guaranteed miss on a path whose only purpose is to be a hit - and, worse,
     * a miss that logs "no KEK for organisation N" about an organisation for which that is the
     * correct and expected state, teaching the reader to ignore the warning that matters.
     */
    @Test
    void aPendingOrganisationIsNotWarmedAtAll() {
        when(organisationRepository.findByTypeOrderByName(OrgType.CARE_PROVIDER))
                .thenReturn(List.of(careProvider(9L, false)));

        runner().run(null);

        verify(keyProvider, never()).keyExists(anyLong());
        verify(keyProvider, never()).currentKeyFor(anyLong());
    }

    /**
     * Warmup is an optimisation, so its worst case must be the behaviour we had before it existed -
     * somebody pays the cold start - and never a refusal to start. An unreachable vault at boot is
     * the realistic case: it may well be reachable by the time a request arrives, and failing
     * startup would turn a slow dependency into an outage.
     */
    @Test
    void anUnreachableVaultDoesNotStopTheApplicationStarting() {
        when(organisationRepository.findByTypeOrderByName(OrgType.CARE_PROVIDER))
                .thenReturn(List.of(careProvider(10L, true), careProvider(11L, true)));
        when(keyProvider.keyExists(10L))
                .thenThrow(new KeyUnavailableException("Key Vault is unreachable"));
        when(keyProvider.keyExists(11L)).thenReturn(true);

        assertThatCode(() -> runner().run(null)).doesNotThrowAnyException();

        // And it carries on to the next organisation rather than abandoning the run: one bad
        // organisation must not cost every other organisation its warm start.
        verify(keyProvider).currentKeyFor(11L);
    }

    /**
     * The budget is real, not decorative. With a zero timeout the deadline has passed before the
     * first organisation, so nothing is asked and startup proceeds immediately - which is what
     * bounds the delay an unreachable vault can add.
     */
    @Test
    void theWarmupBudgetIsEnforced() {
        when(organisationRepository.findByTypeOrderByName(OrgType.CARE_PROVIDER))
                .thenReturn(List.of(careProvider(12L, true)));

        new KeyWarmupRunner(keyProvider, organisationRepository, Duration.ZERO, credential,
                "https://vault.azure.net/.default").run(null);

        verify(keyProvider, never()).keyExists(anyLong());
    }

    @Test
    void warmingNothingIsNotAnError() {
        when(organisationRepository.findByTypeOrderByName(OrgType.CARE_PROVIDER)).thenReturn(List.of());
        assertThatCode(() -> runner().run(null)).doesNotThrowAnyException();
        assertThat(true).isTrue();
    }
}

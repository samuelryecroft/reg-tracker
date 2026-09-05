package ninja.samryecroft.returnhome.tracker.document;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Pays the Azure cold-start cost during startup, so that no user request does.
 *
 * <p><b>The measurement this exists for (T181).</b> On a fresh container the first
 * {@code getKey} took 22.6-32.9 seconds <em>and then failed</em>; once warm the same call took
 * 168-694ms and a whole request 56-664ms. The database was 7ms throughout and the instance was not
 * saturated. The cost was the first managed-identity token acquisition, and AlwaysOn means the
 * trigger is a restart, a deploy or a platform recycle rather than idle sleep - so it is not a
 * once-a-day curiosity, it is <em>the first real user after every deployment</em>, and what they
 * get is a failure rather than a slow page.
 *
 * <p><b>Why an {@link ApplicationRunner} and not an {@code ApplicationReadyEvent} listener.</b>
 * Runners complete <em>before</em> the readiness state is published, so App Service's readiness
 * probe does not pass and traffic is not routed here until this has finished. On the ready event
 * the port is already open and the race is real. This is the difference between "the first user
 * probably will not pay it" and "the first user cannot pay it", and it is the whole point.
 *
 * <p><b>Warmup must not mask a genuinely absent key</b>, which shapes how it asks. It calls
 * {@link KeyProvider#keyExists} first - a pure read with no creation path - and only calls
 * {@link KeyProvider#currentKeyFor} when that came back true. So an organisation whose KEK has not
 * been provisioned is logged and skipped: the key is not created here, no handle is cached for it,
 * and its first real encrypted write fails closed exactly as it did before this class existed.
 * Warming a key into existence would make an unprovisioned organisation look provisioned, which is
 * the opposite of what T168(b) put in place.
 *
 * <p><b>Every failure here is non-fatal.</b> Warmup is an optimisation; the worst case if it does
 * not complete is the behaviour we had before it, so it must never be able to stop the application
 * becoming ready. It is bounded by {@code app.documents.key-vault.warmup-timeout} for the same
 * reason - an unreachable vault must delay startup by a stated amount, not indefinitely.
 */
public class KeyWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KeyWarmupRunner.class);

    private final KeyProvider keyProvider;
    private final OrganisationRepository organisationRepository;
    private final Duration timeout;
    private final TokenCredential credential;
    private final String tokenScope;

    public KeyWarmupRunner(KeyProvider keyProvider, OrganisationRepository organisationRepository,
            Duration timeout, TokenCredential credential, String tokenScope) {
        this.keyProvider = keyProvider;
        this.organisationRepository = organisationRepository;
        this.timeout = timeout;
        this.credential = credential;
        this.tokenScope = tokenScope;
    }

    /**
     * Fetches the Key Vault access token before anything asks the vault for a key.
     *
     * <p><b>Measured, and it is the dominant cold cost.</b> The first {@code getKey} on a fresh
     * container took 32-42 seconds and came back 401. Pam traced the spans: the 401 is Key Vault's
     * standard AAD <em>auth challenge</em>, not a refusal - the vault 401s the first unauthenticated
     * call on purpose, with a {@code WWW-Authenticate} header naming the scope to get a token for.
     * The SDK then acquires the token inline and retries, and the retry succeeds in ~200ms. So the
     * 42 seconds is <b>the challenge leg's span held open while a cold managed-identity token is
     * acquired</b>, at ~10 seconds each on a B1 - not the vault doing forty seconds of work.
     *
     * <p>Which makes the fix "have the token before the challenge arrives" rather than "allow more
     * time for the challenge". A bigger warmup budget would only spend longer in the same dance -
     * and it was that failing call which ate the budget and left the second organisation unwarmed.
     *
     * <p><b>A correction worth leaving here, because it was wrong in the T181 PR body.</b> The two
     * token acquisitions per cold start were reported as duplication caused by two credential
     * instances. They are not: Azure tokens are per audience, and Key Vault
     * ({@code vault.azure.net}) and Blob Storage ({@code storage.azure.com}) are different
     * audiences, so two acquisitions were always correct. Sharing one credential still mattered -
     * two instances mean two caches, so each audience would be fetched afresh - but the count was
     * never going to fall to one, and this pre-acquires only the vault's.
     *
     * <p>Non-fatal, like everything else here: if it fails, the first real call pays what it always
     * paid.
     */
    private Duration preAcquireToken() {
        if (credential == null || tokenScope == null || tokenScope.isBlank()) {
            return Duration.ZERO;
        }
        Instant started = Instant.now();
        try {
            credential.getTokenSync(new TokenRequestContext().addScopes(tokenScope));
            Duration took = Duration.between(started, Instant.now());
            log.info("Key Vault token acquired at startup in {}ms, so the first key lookup does not "
                    + "answer the vault's auth challenge with a cold token", took.toMillis());
            return took;
        } catch (RuntimeException e) {
            log.warn("Could not pre-acquire the Key Vault token ({}); the first key lookup will "
                    + "acquire it inline as before", e.getClass().getSimpleName());
            return Duration.between(started, Instant.now());
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant deadline = Instant.now().plus(timeout);
        Instant started = Instant.now();
        Duration tokenTook = preAcquireToken();
        List<Organisation> organisations = organisationRepository.findByTypeOrderByName(OrgType.CARE_PROVIDER)
                .stream()
                // Only ACTIVE care providers hold records, so only they have a KEK to warm. A
                // PENDING one has not passed the activation gate and by definition has no key yet -
                // asking would be a guaranteed miss on a path whose whole purpose is to be a hit.
                .filter(Organisation::isActive)
                .toList();

        int warmed = 0;
        int absent = 0;
        for (Organisation organisation : organisations) {
            if (Instant.now().isAfter(deadline)) {
                // Says WHERE the budget went, not just that it went. The first version reported
                // only "after N of M organisations", which reads as "the budget is too small" - and
                // when the token pre-acquire took 49 seconds of a 30-second budget on a cold B1, it
                // sent the reader to the wrong lever. A BUDGET-EXHAUSTED MESSAGE THAT DOES NOT SAY
                // WHAT SPENT IT IS AN INVITATION TO RAISE THE BUDGET.
                log.warn("Key warmup ran out of its {}s budget after {} of {} organisations, of "
                                + "which the startup token acquisition took {}ms{}. Starting anyway "
                                + "- the next request pays what is left",
                        timeout.toSeconds(), warmed + absent, organisations.size(),
                        tokenTook.toMillis(),
                        tokenTook.compareTo(timeout) >= 0
                                ? " - THE TOKEN ALONE EXCEEDED THE BUDGET, so a larger budget would "
                                        + "only spend longer on the same call"
                                : "");
                return;
            }
            try {
                if (!keyProvider.keyExists(organisation.getId())) {
                    // Not created here: see the class comment. An organisation reaching this state
                    // is a provisioning gap, and the loud place for it to surface is its first
                    // record, not a startup log that nobody reads.
                    log.warn("No KEK exists for active organisation {} ({}); not warming, and not "
                            + "creating one", organisation.getId(), organisation.getName());
                    absent++;
                    continue;
                }
                keyProvider.currentKeyFor(organisation.getId());
                warmed++;
            } catch (RuntimeException e) {
                // Including KeyUnavailableException. Startup continues: a vault that is unreachable
                // now may well be reachable by the time a request arrives, and refusing to start
                // would turn a slow dependency into an outage.
                log.warn("Could not warm the KEK for organisation {}: {}", organisation.getId(),
                        e.getMessage());
                absent++;
            }
        }
        log.info("Key warmup finished in {}ms: {} warmed, {} skipped",
                Duration.between(started, Instant.now()).toMillis(), warmed, absent);
    }
}

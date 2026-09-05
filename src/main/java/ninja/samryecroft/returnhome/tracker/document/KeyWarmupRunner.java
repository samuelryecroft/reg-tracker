package ninja.samryecroft.returnhome.tracker.document;

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

    public KeyWarmupRunner(KeyProvider keyProvider, OrganisationRepository organisationRepository,
            Duration timeout) {
        this.keyProvider = keyProvider;
        this.organisationRepository = organisationRepository;
        this.timeout = timeout;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant deadline = Instant.now().plus(timeout);
        Instant started = Instant.now();
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
                log.warn("Key warmup ran out of its {}s budget after {} of {} organisations; "
                                + "starting anyway - the next request pays what is left",
                        timeout.toSeconds(), warmed + absent, organisations.size());
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

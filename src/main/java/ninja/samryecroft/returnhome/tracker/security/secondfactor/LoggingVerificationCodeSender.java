package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import jakarta.annotation.PostConstruct;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import ninja.samryecroft.returnhome.tracker.config.DeployedEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The fallback sender: writes the code to the log instead of sending mail.
 *
 * <p>It exists so that local development and the test suite have a working second factor without a
 * mail account. Which sender is active is chosen by {@code app.security.second-factor.transport},
 * the same shape {@code app.documents.storage} already uses to pick a document backend - an explicit
 * named choice rather than an inference.
 *
 * <p><b>{@code @ConditionalOnMissingBean} was the first attempt and is silently broken here.</b> On
 * a scanned {@code @Component} that condition is evaluated before the beans it asks about are
 * registered, so the fallback was skipped, no {@code VerificationCodeSender} existed at all, and
 * every context in the application failed to start - 407 tests, none of them about the second
 * factor. The annotation is built for auto-configuration and quietly does nothing useful outside it.
 *
 * <p><b>It refuses to start a deployed environment</b>, asking {@link DeployedEnvironment} rather
 * than holding its own list of profile names. The first version of this class carried
 * {@code {azure, prod, production}} - which is a second answer to a question that already has one,
 * and it had already drifted: it was missing {@code staging}. That is precisely the defect T189
 * consolidated away, where four such lists existed and two had never fired in production because
 * they did not name the profile production actually runs under. A sender that prints one-time codes into a
 * log - and this application ships its logs to Log Analytics - is a credential disclosure, so the
 * guard below is a startup failure rather than a warning. This is the same shape
 * {@code DocumentStorageConfig} uses to refuse a production boot on a local key provider: the
 * decision is made where it can still fail loudly, not at the moment a code is written to a log.
 *
 * <p>Restricting it by {@code @Profile} instead was the first attempt and was wrong: it removes the
 * bean from every context that is not dev or test, so <em>every</em> integration test - and any
 * deployment with the factor switched off - would fail to start for want of a sender it never
 * needed. The guard therefore tests what actually matters, which is not "which profile is this?" but
 * <b>"is a real second factor about to run through a fake sender?"</b>
 */
@Component
@ConditionalOnProperty(prefix = "app.security.second-factor", name = "transport",
        havingValue = "log", matchIfMissing = true)
public class LoggingVerificationCodeSender implements VerificationCodeSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingVerificationCodeSender.class);

    private final Environment environment;
    private final AppProperties appProperties;

    public LoggingVerificationCodeSender(Environment environment, AppProperties appProperties) {
        this.environment = environment;
        this.appProperties = appProperties;
    }

    @PostConstruct
    void refuseToRunInADeployedEnvironment() {
        if (!appProperties.getSecurity().getSecondFactor().isEnabled()) {
            return;
        }
        if (DeployedEnvironment.isDeployed(environment)) {
            throw new IllegalStateException(
                    "The second factor is enabled but no VerificationCodeSender is configured, so "
                            + "codes would be written to the application log. Configure a real "
                            + "sender, or turn app.security.second-factor.enabled off.");
        }
    }

    @Override
    public void send(String emailAddress, String code) {
        log.warn("DEVELOPMENT SECOND FACTOR - code for {} is {}. This sender is never used in a "
                + "deployed environment; see refuseToRunInADeployedEnvironment().", emailAddress, code);
    }
}

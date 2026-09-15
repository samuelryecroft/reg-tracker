package ninja.samryecroft.returnhome.tracker.security.passwordreset;

import com.azure.core.credential.TokenCredential;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resolves the one {@link PasswordResetLinkSender} from the transport knob (T353d), the same knob the
 * sign-in code sender reads (T353j).
 *
 * <p><b>A factory, not a pair of {@code @ConditionalOnProperty} components, on purpose.</b> The code
 * sender's Logging impl is {@code havingValue="log", matchIfMissing=true}, so a test that sets
 * {@code transport=capturing} gets NEITHER the log nor the acs code sender and must register its own.
 * That burden is fine for one sender the tests already know about; it is a trap for a SECOND sender
 * those same tests never mention - the context would fail to load because
 * {@code PasswordResetRequestService} could not be built. So this always returns exactly one bean:
 * ACS when {@code transport=acs}, and the logging sender for every other value (log, capturing,
 * unset), so no test has to know this sender exists for its context to load. A test that wants to
 * capture links registers a {@code @Primary} bean, which wins over this one.
 */
@Configuration
class PasswordResetLinkSenderConfig {

    @Bean
    PasswordResetLinkSender passwordResetLinkSender(AppProperties appProperties,
            ObjectProvider<TokenCredential> credential,
            @Value("${app.security.second-factor.acs-endpoint:}") String acsEndpoint) {
        if ("acs".equals(appProperties.getSecurity().getSecondFactor().getTransport())) {
            return new AcsPasswordResetLinkSender(appProperties, credential.getObject(), acsEndpoint);
        }
        return new LoggingPasswordResetLinkSender();
    }
}

package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.core.credential.TokenCredential;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The second factor has TWO switches, and only one of them is the one everybody checks.
 *
 * <p>{@code app.security.second-factor.enabled} decides whether a user is challenged.
 * {@code app.security.second-factor.transport} decides which sender bean is CREATED - and a bean is
 * created at startup whether or not the feature is switched on. So a deployment can have the factor
 * demonstrably off and still fail to boot, because choosing {@code acs} makes
 * {@code ACS_EMAIL_ENDPOINT} load-bearing at construction time.
 *
 * <p>That is why the azure profile must not pin the transport to a literal. Reviewing this change
 * asks "is the factor off?", the answer is yes, and the answer is not the one that matters. The
 * transport is chosen by the deployment at the moment its infrastructure exists - which is the
 * property {@link AcsSenderIsSelectedOnlyByExplicitTransportTest} already claims and could not see,
 * because it inspects the sender's source while the selection lives in a properties file.
 */
class TheAzureProfileMustNotDemandInfrastructureThatIsNotThereYetTest {

    private static final Path AZURE_PROFILE =
            Path.of("src/main/resources/application-azure.properties");

    /**
     * The transport must come from the environment, never a literal.
     *
     * <p>A literal here is a dated assertion that the infrastructure exists, written in a file that
     * ships ahead of it. The environment variable is the same decision made where the answer is
     * actually known.
     */
    @Test
    void theAzureProfileLeavesTheTransportToTheDeployment() throws IOException {
        String profile = Files.readString(AZURE_PROFILE);
        Matcher transport = Pattern
                .compile("^app\\.security\\.second-factor\\.transport=(.*)$", Pattern.MULTILINE)
                .matcher(profile);

        assertThat(transport.find())
                .as("the azure profile should still say something about the transport")
                .isTrue();
        assertThat(transport.group(1).trim())
                .as("the transport must be environment-driven, not a literal. Pinning it to 'acs' "
                        + "creates the ACS sender on every boot of this profile, which makes "
                        + "ACS_EMAIL_ENDPOINT required at startup - so a release lands before the "
                        + "resource exists and production fails to start, with the factor still off")
                .startsWith("${");
    }

    /**
     * The consequence itself, seen rather than reasoned about: choose {@code acs} without an
     * endpoint and the context does not start.
     *
     * <p>This is the positive control for the test above - it is what makes "must be
     * environment-driven" a safety property rather than a style preference.
     */
    @Test
    void choosingAcsWithoutAnEndpointRefusesToStart() {
        new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                        .of(ConfigurationPropertiesAutoConfiguration.class,
                                PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(SenderUnderTest.class)
                .withPropertyValues(
                        "app.security.second-factor.transport=acs",
                        "app.security.second-factor.from-address=noreply@example.org")
                .run(context -> assertThat(context)
                        .as("transport=acs with no ACS_EMAIL_ENDPOINT must fail the context, which "
                                + "in a deployment means the app does not come up at all")
                        .hasFailed());
    }

    /**
     * The positive control: the same context, same shape, with the endpoint present - it starts and
     * the bean is there. Without this, the failure above could just as well be a broken test.
     */
    @Test
    void theSameContextStartsOnceTheEndpointIsSupplied() {
        new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                        .of(ConfigurationPropertiesAutoConfiguration.class,
                                PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(SenderUnderTest.class)
                .withPropertyValues(
                        "app.security.second-factor.transport=acs",
                        "app.security.second-factor.from-address=noreply@example.org",
                        "app.security.second-factor.acs-endpoint=https://example.communication.azure.com")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(AcsVerificationCodeSender.class));
    }

    /**
     * The second route to the same outage, and the one the transport fix does NOT cover.
     *
     * <p>{@code acs-endpoint} is a {@code @Value} on a conditional bean, so it is read only if that
     * bean is built. {@code from-address} is not: it is bound through {@code @ConfigurationProperties},
     * which binds on EVERY boot regardless of transport. If an unresolvable placeholder fails that
     * binding, the azure profile cannot start without SECOND_FACTOR_FROM_ADDRESS either - and no
     * amount of leaving the transport alone would save it.
     */
    @Test
    void anUnresolvedFromAddressMustNotStopTheApplicationBooting() {
        new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                        .of(ConfigurationPropertiesAutoConfiguration.class,
                                PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(SenderUnderTest.class)
                .withPropertyValues(
                        "app.security.second-factor.transport=log",
                        "app.security.second-factor.from-address=${SECOND_FACTOR_FROM_ADDRESS}")
                .run(context -> {
                    assertThat(context)
                            .as("with no real transport chosen, an unset sender address must not "
                                    + "stop the application booting - it is not needed until acs "
                                    + "is chosen")
                            .hasNotFailed();
                    // The fact that makes the sender's own guard insufficient, recorded as an
                    // assertion rather than a comment: binding does not fail, it keeps the literal
                    // placeholder text - which is not blank, and so passes an isBlank() check.
                    assertThat(context.getBean(AppProperties.class).getSecurity().getSecondFactor()
                            .getFromAddress())
                            .as("an unresolved placeholder binds as its own literal text")
                            .isEqualTo("${SECOND_FACTOR_FROM_ADDRESS}");
                });
    }

    /**
     * And therefore: choosing acs while leaving the sender address unset must fail the boot.
     *
     * <p>Without this the sender is built with a from-address of {@code ${SECOND_FACTOR_FROM_ADDRESS}}
     * and the first user to sign in gets a delivery failure - the exact "looks healthy until somebody
     * tries to log in" case the constructor's guard was written to prevent, walking straight through
     * it because the placeholder text is not blank.
     */
    @Test
    void choosingAcsWithAnUnresolvedFromAddressRefusesToStart() {
        new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                        .of(ConfigurationPropertiesAutoConfiguration.class,
                                PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(SenderUnderTest.class)
                .withPropertyValues(
                        "app.security.second-factor.transport=acs",
                        "app.security.second-factor.acs-endpoint=https://example.communication.azure.com",
                        "app.security.second-factor.from-address=${SECOND_FACTOR_FROM_ADDRESS}")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(AppProperties.class)
    @Import(AcsVerificationCodeSender.class)
    static class SenderUnderTest {

        /**
         * The shared credential the sender injects. A real stub rather than a mock on purpose: a
         * mock that cannot initialise fails the context for its own reasons, which would make the
         * "refuses to start" test above pass while proving nothing about the endpoint.
         */
        @Bean
        TokenCredential tokenCredential() {
            return request -> Mono.empty();
        }
    }
}

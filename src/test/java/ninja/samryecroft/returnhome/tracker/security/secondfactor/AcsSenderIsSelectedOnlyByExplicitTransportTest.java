package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Guards the two properties of the ACS sender that cannot be asserted by calling it: what the message
 * is allowed to contain, and that nothing selects it by accident.
 *
 * <p>There is no test here that sends mail. The value of one would be a mock asserting we called the
 * SDK, which proves only that the code we wrote is the code we wrote. <b>The acceptance test for
 * delivery is a real code arriving in a real mailbox from the deployed environment</b> - stated on
 * the card, and not something a unit test can stand in for.
 */
class AcsSenderIsSelectedOnlyByExplicitTransportTest {

    private static final Path SENDER = Path.of("src/main/java/ninja/samryecroft/returnhome/tracker/"
            + "security/secondfactor/AcsVerificationCodeSender.java");

    /**
     * The message may carry the code and nothing else.
     *
     * <p>This is what makes the data location a paperwork question rather than an exposure one - the
     * human reached the same conclusion independently: <i>"no PII should ever be in this message
     * anyway"</i>. Mail leaves our estate, so the standing rule is that a leaked or misdelivered code
     * is worth nothing on its own. <b>The realistic way that stops being true is somebody adding
     * context to be helpful</b> - a name in the greeting, a case reference "so they know which one" -
     * which reads as an improvement while turning a harmless message into a disclosure.
     */
    @Test
    void theMessageBodyCarriesNoIdentifiers() throws IOException {
        String source = Files.readString(SENDER).toLowerCase(Locale.ROOT);
        int body = source.indexOf("setbodyplaintext");
        assertThat(body).as("the message body should still be built here").isGreaterThan(0);
        String message = source.substring(body, Math.min(body + 600, source.length()));

        assertThat(message)
                .as("the second-factor email must carry the code and nothing else - no name, no case "
                        + "or interview reference, nothing naming the young person this session is "
                        + "about to open. If you are adding context to be helpful, that is the change "
                        + "this test exists to stop")
                .doesNotContain("getfullname")
                .doesNotContain("getfirstname")
                .doesNotContain("child")
                .doesNotContain("interview")
                .doesNotContain("case");
    }

    /**
     * Selected only by an explicit {@code transport=acs}, never by a default or a profile.
     *
     * <p>The same shape {@code app.documents.storage} uses: a named choice rather than an inference.
     * A transport that could switch on as a side effect of something else is one nobody decided to
     * open - and the failure would be silent, because sending real mail looks exactly like working.
     */
    @Test
    void nothingSelectsTheAcsSenderImplicitly() throws IOException {
        String source = Files.readString(SENDER);
        assertThat(source)
                .as("the ACS sender must be selected by an explicit transport value, not by a "
                        + "profile, a default, or @ConditionalOnMissingBean")
                .contains("havingValue = \"acs\"")
                .doesNotContain("matchIfMissing = true")
                .doesNotContain("@Profile");
    }
}

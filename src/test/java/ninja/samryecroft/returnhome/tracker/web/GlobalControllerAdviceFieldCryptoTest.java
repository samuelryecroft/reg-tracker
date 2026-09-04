package ninja.samryecroft.returnhome.tracker.web;

import static org.assertj.core.api.Assertions.assertThat;

import ninja.samryecroft.returnhome.tracker.document.KeyUnavailableException;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.FieldCryptoException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

/**
 * T168: a field-crypto failure caused by a missing/unreachable KEK must map to 503 (transient,
 * "provision the key / contact your administrator"), the same way the document path already does -
 * not the opaque 500 that a not-yet-provisioned organisation KEK produced on add-child before this.
 * A genuine (non-key) crypto failure still fails closed as a 500. The handler under test uses none
 * of the advice's collaborators, so they are left null deliberately.
 */
class GlobalControllerAdviceFieldCryptoTest {

    private final GlobalControllerAdvice advice =
            new GlobalControllerAdvice(null, null, null, null, null, null);

    @Test
    void missingKekMapsToServiceUnavailable() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Model model = new ExtendedModelMap();

        FieldCryptoException ex = new FieldCryptoException(
                "Could not create a field key for organisation 2",
                new KeyUnavailableException(
                        "No key exists for organisation 2 and key creation is disabled; provision "
                                + "org-2-kek before its first report"));

        String view = advice.handleFieldCrypto(ex, model, response);

        assertThat(view).isEqualTo("error");
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(model.getAttribute("status")).isEqualTo(503);
        // The user message stays generic - the actionable "provision org-2-kek" detail is logged, not shown.
        assertThat(model.getAttribute("message")).asString().doesNotContain("org-2-kek");
    }

    @Test
    void missingKekIsFoundDeepInTheCauseChain() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Model model = new ExtendedModelMap();

        FieldCryptoException ex = new FieldCryptoException("wrapper",
                new IllegalStateException("intermediate",
                        new KeyUnavailableException("key missing")));

        advice.handleFieldCrypto(ex, model, response);

        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void aGenuineCryptoFailureStillFailsClosedAs500() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Model model = new ExtendedModelMap();

        FieldCryptoException ex = new FieldCryptoException(
                "Could not unwrap the field key for organisation 2",
                new IllegalStateException("AES-GCM tag mismatch"));

        String view = advice.handleFieldCrypto(ex, model, response);

        assertThat(view).isEqualTo("error");
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(model.getAttribute("status")).isEqualTo(500);
    }
}

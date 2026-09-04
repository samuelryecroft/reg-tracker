package ninja.samryecroft.returnhome.tracker.web;

import static org.assertj.core.api.Assertions.assertThat;

import ninja.samryecroft.returnhome.tracker.document.KeyUnavailableException;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.FieldCryptoException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

/**
 * T168: unit-tests the {@code handleFieldCrypto} mapping in isolation - a missing/unreachable KEK
 * (a {@link KeyUnavailableException} in the cause chain) maps to 503, a genuine crypto failure to a
 * fail-closed 500. Note this proves the mapping, not that Spring selects this handler: the live
 * add-child failure already returned 503 via {@code handleDocumentSecurity} (which matches the
 * KeyUnavailableException cause), so this handler's job is to change the <em>message</em> from the
 * document-path wording to an add-child-appropriate one, at the same 503. The end-to-end routing +
 * render test proves the selection and that the notice reaches the page. The handler under test uses
 * none of the advice's collaborators, so they are left null deliberately.
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

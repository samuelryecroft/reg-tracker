package ninja.samryecroft.returnhome.tracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T119 spec §7l (4c, sign in - the half knowable today). D-4c-3: the {@code ▲}/{@code ✓} glyph
 * literals are vendored Phosphor icons now, not a character doing an icon's job. D-4c-5:
 * {@code required} on both fields - the server-rendered banner stays the authority for a no-JS
 * submit, this only saves the round trip the browser can already catch.
 *
 * <p>D-4c-1's own lockout banner is deliberately untouched (T215, gated on Kevin's enumeration-
 * oracle ruling) - not this test's concern.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginPageMarkupIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void requiredIsSetOnBothFields() throws Exception {
        String html = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"username\"").contains("id=\"password\"");
        // Both inputs' own tags carry required - not asserting a specific attribute order, since
        // Thymeleaf's own attribute ordering is an implementation detail, not part of the contract.
        int usernameTag = html.indexOf("id=\"username\"");
        int usernameTagEnd = html.indexOf('>', usernameTag);
        int passwordTag = html.indexOf("id=\"password\"");
        int passwordTagEnd = html.indexOf('>', passwordTag);
        assertThat(html.substring(usernameTag, usernameTagEnd)).contains("required");
        assertThat(html.substring(passwordTag, passwordTagEnd)).contains("required");
    }

    @Test
    void theErrorAndLogoutGlyphsAreVendoredIconsNotCharacterLiterals() throws Exception {
        String errorHtml = mockMvc.perform(get("/login").param("error", ""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(errorHtml).contains("#ph-warning-circle");
        assertThat(errorHtml).doesNotContain("▲");

        String logoutHtml = mockMvc.perform(get("/login").param("logout", ""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(logoutHtml).contains("#ph-check-circle");
        assertThat(logoutHtml).doesNotContain("✓");
    }
}

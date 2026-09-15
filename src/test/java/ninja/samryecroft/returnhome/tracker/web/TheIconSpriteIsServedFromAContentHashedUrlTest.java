package ninja.samryecroft.returnhome.tracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.TestLogins;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The icon sprite is emitted at a CONTENT-HASHED url, so a changed sprite reaches returning browsers
 * despite its one-year cache lifetime (T349 follow-up).
 *
 * <p><b>Why this is a rendered-byte assertion and not a comment.</b> The sprite is served with
 * {@code Cache-Control: max-age=365d} on {@code /icons/phosphor.svg}. If a template ever referenced
 * it at that PLAIN path instead of through a {@code @{...}} link expression, a returning browser
 * would keep the old sprite for up to a year - so when the subsetted sprite (or any future icon
 * change) shipped, that browser would silently never see it. The protection is that every reference
 * is a {@code @{/icons/phosphor.svg}} link expression and
 * {@code spring.web.resources.chain.strategy.content.paths=/**} rewrites it to
 * {@code /icons/phosphor-<hash>.svg} at render time; a changed sprite changes the hash changes the
 * url, defeating the cache.
 *
 * <p>That protection is invisible to a reader and easy to break by pasting a literal path. This test
 * renders an authenticated page and asserts on the emitted markup, so <b>the day someone hardcodes a
 * plain {@code /icons/phosphor.svg}, the build says so</b> rather than a user holding a stale sprite
 * for a year. The CSS is content-hashed by the identical mechanism; this pins the sprite to it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TheIconSpriteIsServedFromAContentHashedUrlTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    @Test
    void theNavEmitsTheSpriteAtAContentHashedUrlAndNeverThePlainPath() throws Exception {
        String username = "t349-sprite-" + System.nanoTime();
        User admin = new User();
        admin.setUsername(username);
        admin.setFirstName("Sprite");
        admin.setLastName("Guard");
        admin.setEmail(username + "@example.test");
        admin.setPassword(passwordEncoder.encode("correct-horse-battery-staple"));
        admin.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        admin.setEnabled(true);
        userRepository.save(admin);

        UserDetails details = appUserDetailsService.loadUserByUsername(TestLogins.loginIdentifier(username));
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        RequestPostProcessor asAdmin = securityContext(context);

        // Any authenticated page wraps the shared nav (fragments/layout.html), which references the
        // sprite via th:with="ico=@{/icons/phosphor.svg}".
        String html = mockMvc.perform(get("/admin/organisations").with(asAdmin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Positive: the emitted url carries a content hash between "phosphor-" and ".svg".
        assertThat(html)
                .as("the sprite must be emitted at a content-hashed url so a changed sprite defeats the year-long cache")
                .containsPattern("/icons/phosphor-[0-9a-f]{8,}\\.svg");

        // Negative, and this is the one that catches a future regression: the PLAIN path must never
        // appear in rendered markup. The hashed url ("phosphor-<hash>.svg") does not contain this
        // substring, so a match here means someone referenced the sprite without a @{...} link
        // expression - the exact mistake that would silently strand returning browsers on an old
        // sprite for a year.
        assertThat(html)
                .as("no template may reference the sprite at its unversioned path")
                .doesNotContain("/icons/phosphor.svg");
    }
}

package ninja.samryecroft.returnhome.tracker.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;

/**
 * T215 / D-4c-1: the failure handler picks its message from {@link LoginAttemptService}, never from
 * the exception type.
 *
 * <p><b>These two cases are the whole card, and they are deliberately contradictory.</b> Each pairs
 * an exception with the opposite lock state, so a handler that consults the exception fails one and
 * a handler that consults the service passes both. Nothing else distinguishes the two
 * implementations - which is exactly why the wrong one is invisible.
 *
 * <p>Why it matters, restated because it is the part that is easy to lose: a <b>real</b> locked
 * account fails with {@code LockedException}; an <b>unknown</b> username that is equally locked
 * still fails with {@code BadCredentialsException}, because {@code loadUserByUsername} throws
 * before the lock is consulted. Branch on the type and the locked banner reaches only accounts that
 * exist, and <b>the fix becomes the username enumeration oracle it was written to prevent</b>.
 *
 * <p>A plain unit test on purpose: this machine has no Docker, so an integration test could only
 * ever run in CI. The property under test is a branch, and a branch does not need a database.
 */
class LoginFailureHandlerTest {

    private final LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);
    private final LoginFailureHandler handler = new LoginFailureHandler(loginAttemptService);

    private String redirectFor(String username, boolean locked, AuthenticationException exception)
            throws IOException {
        when(loginAttemptService.isLocked(username)).thenReturn(locked);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setParameter(LoginFailureHandler.USERNAME_PARAMETER, username);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response, exception);

        return response.getRedirectedUrl();
    }

    @Test
    void anUnknownUsernameThatIsLockedStillGetsTheLockedPageEvenThoughItFailedOnBadCredentials()
            throws IOException {
        // The oracle case. An unknown username never reaches the lock check inside Spring, so it
        // arrives here as BadCredentialsException - and it must still be told the same thing a
        // real locked account is told, or the difference between the two IS the disclosure.
        String url = redirectFor("no-such-person", true, new BadCredentialsException("Bad credentials"));

        assertThat(url)
                .as("a handler that branched on the exception type would send this to the generic "
                        + "page, so the locked banner would appear only for accounts that exist - "
                        + "and the fix would introduce the enumeration oracle it was written to "
                        + "prevent. The lock state comes from LoginAttemptService, which is "
                        + "username-blind")
                .isEqualTo(LoginFailureHandler.LOCKED_FAILURE_URL);
    }

    @Test
    void aLockedExceptionOnAnAccountThatIsNoLongerLockedGetsTheGenericPage() throws IOException {
        // The mirror. isLocked() clears an elapsed lockout as it reads, so the service can say
        // "not locked" for an attempt that Spring still rejected. The service is the authority on
        // what the reader is told, in both directions.
        String url = redirectFor("real-person", false, new LockedException("Account is locked"));

        assertThat(url)
                .as("the exception is not the authority here; the counter is")
                .isEqualTo(LoginFailureHandler.GENERIC_FAILURE_URL);
    }

    @Test
    void anOrdinaryWrongPasswordStillGetsTheGenericPage() throws IOException {
        assertThat(redirectFor("real-person", false, new BadCredentialsException("Bad credentials")))
                .isEqualTo(LoginFailureHandler.GENERIC_FAILURE_URL);
    }

    @Test
    void aMissingUsernameParameterDoesNotThrow() throws IOException {
        // A POST with no username at all reaches this handler; isLocked(null) answers false, and
        // the page must still render rather than 500 on the way to saying "check your details".
        when(loginAttemptService.isLocked(null)).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response, new BadCredentialsException("Bad credentials"));

        assertThat(response.getRedirectedUrl()).isEqualTo(LoginFailureHandler.GENERIC_FAILURE_URL);
    }
}

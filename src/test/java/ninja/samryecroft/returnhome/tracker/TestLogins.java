package ninja.samryecroft.returnhome.tracker;

/**
 * T344: sign-in resolves an account by EMAIL, but the fixtures across this suite name their
 * accounts by username and derive the address from it as {@code <username>@example.test}.
 *
 * <p>This turns a fixture's name for an account into the identifier the application will actually
 * accept. It is a translation of the test suite's own naming convention and nothing more - it
 * carries no product behaviour, and in particular it does NOT mean the application accepts a bare
 * username: {@link ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService} resolves by
 * email, with the single narrow break-glass exception, and there are tests asserting that directly.
 *
 * <p>An identifier that already contains an {@code @} is passed through untouched, so a test that
 * means a specific address - or means to prove a bad one is refused - still says exactly what it
 * meant.
 */
public final class TestLogins {

    /**
     * The seeded break-glass account's name - the default of {@code app.admin.username}, which the
     * tests do not override.
     *
     * <p>This is the ONE account that still signs in by name, because it deliberately has no email
     * address: it is the way back in when email is what has broken. So the convention below must
     * NOT be applied to it - appending a domain would produce an identifier that resolves to
     * nothing, and the failure would look like a broken password rather than a broken test helper.
     */
    public static final String BREAK_GLASS_USERNAME = "admin";

    private TestLogins() {
    }

    public static String loginIdentifier(String usernameOrEmail) {
        if (usernameOrEmail == null
                || usernameOrEmail.contains("@")
                || BREAK_GLASS_USERNAME.equals(usernameOrEmail)) {
            return usernameOrEmail;
        }
        return usernameOrEmail + "@example.test";
    }
}

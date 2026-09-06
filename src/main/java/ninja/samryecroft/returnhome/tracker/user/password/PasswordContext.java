package ninja.samryecroft.returnhome.tracker.user.password;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The values a password for THIS account must not be built out of (T272 R2).
 *
 * <p>These are the half of the blocklist that matters here. A generic top-10k list contains
 * {@code hunter2}; it does not contain {@code returnhome2026}, and this population's weak password
 * is the second kind. So the account's own username, the local-part of its email address, its
 * organisation's name and the application's name are checked as substrings, case-insensitively.
 *
 * <p>Every field is optional, and a caller that cannot supply one passes null rather than a
 * placeholder: a context value of {@code ""} would match every password, and one of {@code "none"}
 * would quietly ban a real word. <strong>Each caller documents which values it can supply and
 * why</strong>, so a gap is a stated limitation rather than an accident.
 *
 * @param username         the account's username, if known at validation time
 * @param email            the full email address; only its LOCAL-PART is used, since every address in
 *                          one organisation shares a domain and banning it would ban a word every
 *                          user has in common
 * @param organisationName the organisation's display name, if it has been resolved
 */
public record PasswordContext(String username, String email, String organisationName) {

    /** Values short enough to be meaningless are dropped: banning a 2-letter substring bans everything. */
    private static final int SHORTEST_USEFUL_VALUE = 4;

    /**
     * Context values are also split into WORDS, and this is why.
     *
     * <p>The ruling names the target directly: this population's weak password is
     * {@code returnhome2026}. Whole-value matching alone does NOT catch it - the application is
     * called {@code return-home-tracker}, and {@code returnhome2026} does not contain that string.
     * A check that misses the one example the rule was written for would be the rule in name only.
     *
     * <p>SIX, not four, and the difference is the whole reason for a separate threshold. Four admits
     * {@code home} and {@code care} - ordinary English words that a long passphrase may contain
     * innocently, and rejecting those costs a care worker a password they had good reason to choose.
     * Six keeps {@code return} and {@code tracker}, which are what actually make a password
     * guessable to anyone who knows the service. A whole context value stays at four because it is
     * specific to this account rather than generic.
     *
     * <p><strong>SIX IS A PROXY FOR DISTINCTIVENESS, and that is what stops anyone lowering it.</strong>
     * It is not a length rule; it is a cheap stand-in for "is this word specific to us". It works
     * because of a property of THIS DOMAIN rather than a general truth: the non-distinctive
     * vocabulary here is short - home, care, team, unit - while the distinctive tokens are longer -
     * return, tracker, real organisation names. Lower it and the short generic words come back in,
     * and every care worker whose passphrase contains "home" is refused.
     *
     * <p>A short but distinctive ORGANISATION name does not fall through this gap, which is worth
     * saying because it is the objection a reader will reach for: whole context values are matched
     * at {@link #SHORTEST_USEFUL_VALUE} (four), and only WORDS SPLIT OUT of a multi-word value need
     * six. So an organisation called "Oaks" is matched whole, and {@code oaks2026} is caught.
     *
     * <p>Confirmed by Kevin (T280) after reading the code rather than the summary. It remains an
     * implementation choice rather than a ruled one - the ruling says "four context values" and does
     * not say how they are matched - and is recorded here so it can be argued with rather than
     * discovered.
     */
    private static final int SHORTEST_USEFUL_WORD = 6;

    private static final java.util.regex.Pattern NON_ALPHANUMERIC =
            java.util.regex.Pattern.compile("[^a-z0-9]+");

    public static PasswordContext none() {
        return new PasswordContext(null, null, null);
    }

    /** Lower-cased, de-noised, application name included. Never contains null, blank or trivial values. */
    List<String> significantValues(String applicationName) {
        List<String> values = new ArrayList<>();
        add(values, username);
        add(values, localPartOf(email));
        add(values, organisationName);
        add(values, applicationName);
        return values;
    }

    private static void add(List<String> values, String value) {
        if (value == null) {
            return;
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        if (normalised.length() >= SHORTEST_USEFUL_VALUE) {
            values.add(normalised);
        }
        for (String word : NON_ALPHANUMERIC.split(normalised)) {
            if (word.length() >= SHORTEST_USEFUL_WORD && !values.contains(word)) {
                values.add(word);
            }
        }
    }

    private static String localPartOf(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}

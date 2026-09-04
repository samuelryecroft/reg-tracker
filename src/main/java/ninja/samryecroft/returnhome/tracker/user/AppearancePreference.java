package ninja.samryecroft.returnhome.tracker.user;

/**
 * T138 batch 1b (Nocturne phase 2): a per-user appearance setting, rendered server-side onto
 * {@code <html data-appearance="...">} with no flash (design spec §2.3). {@code AUTO} follows the
 * signed-in user's OS-level {@code prefers-color-scheme} (via app.css's
 * {@code :root[data-appearance="auto"]} block); {@code LIGHT}/{@code DARK} are explicit,
 * account-level overrides that win regardless of what the OS reports.
 *
 * <p>{@code AUTO} is the default for every account, new and existing (R-Q9, closed): some people
 * set a dark OS theme for photophobia or migraine and others set light for astigmatism, so
 * honouring whichever choice a user has already made is the accessible default, not Nocturne's own
 * dark-first default.
 */
public enum AppearancePreference {
    LIGHT,
    DARK,
    AUTO
}

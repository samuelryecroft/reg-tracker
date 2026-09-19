package ninja.samryecroft.returnhome.tracker.export;

/**
 * The free-text reference an operator attaches to an export - a court reference, a request number,
 * the reason in their own words - and the one bound on it (T379, CODE-REVIEW-2026-09-18 P1.2).
 *
 * <p>Bounded because it is copied verbatim into the audit row that records the disclosure, and
 * that row's metadata is cut at the column length from the end. A reference long enough to reach
 * the cut would have cost the row whatever came after it. The publisher now also puts the
 * reference last, so what a cut could lose is the reference itself and never the checksum or the
 * counts; this bound is what makes the cut unreachable in the first place. Both forms carry the
 * same number as {@code maxlength}, and a test holds them to it.
 */
public final class ExportReference {

    public static final int MAX_LENGTH = 200;

    private ExportReference() {
    }

    /** Null and blank are fine - the reference is optional; only its length is bounded. */
    public static boolean fits(String reference) {
        return reference == null || reference.length() <= MAX_LENGTH;
    }

    public static String tooLongMessage() {
        return "The reference must be " + MAX_LENGTH + " characters or fewer";
    }
}

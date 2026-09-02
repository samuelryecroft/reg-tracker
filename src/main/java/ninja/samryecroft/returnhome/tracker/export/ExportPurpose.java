package ninja.samryecroft.returnhome.tracker.export;

/**
 * Why this export is being produced. Mandatory on every export and a <strong>closed</strong> list:
 * free text here produces prose nobody reads, and - worse - unexpected personal data sitting in an
 * audit table that is itself subject to disclosure.
 *
 * <p>The stated purpose goes in the audit row and on the pack's cover sheet, so the artefact says on
 * its own face why it was produced.
 *
 * <p><strong>Subject access requests are deliberately absent.</strong> A SAR is not an evidence
 * pack: it is a data subject exercising a right on a statutory clock, and the response must be
 * reviewed for third-party personal data before release. These packs do the opposite - they bundle
 * staff details and activity involving other people. Offering it as an option would imply the tool
 * does that job, and the foreseeable outcome is an unredacted pack sent to a parent or a young
 * person, which cannot be taken back. The screen signposts a manual route instead.
 */
public enum ExportPurpose {

    REGULATORY_INSPECTION("Ofsted or regulatory inspection"),
    INTERNAL_SAFEGUARDING_REVIEW("Internal safeguarding review"),
    SERIOUS_CASE_REVIEW("Serious case / practice review"),
    TRANSFER_TO_PLACING_AUTHORITY("Transfer to a placing local authority"),
    LEGAL_PROCEEDINGS("Legal proceedings");

    private final String label;

    ExportPurpose(String label) {
        this.label = label;
    }

    /** The wording shown on screen and printed on the cover sheet - the two must not drift apart. */
    public String getLabel() {
        return label;
    }
}

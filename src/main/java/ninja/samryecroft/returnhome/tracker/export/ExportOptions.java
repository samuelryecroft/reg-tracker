package ninja.samryecroft.returnhome.tracker.export;

/**
 * What the requester chose on the export screen. {@code purpose} is mandatory and comes from a
 * closed list (Oscar's D-3 - "Subject access or information request" removed from MVP, these packs
 * are not SAR responses). {@code reference} is optional free text (a case or inspection reference).
 */
public record ExportOptions(String purpose, String reference) {

    /** Oscar's D-3: five purposes, SAR dropped - the pack does the opposite of a SAR's third-party redaction. */
    public static final java.util.List<String> PURPOSES = java.util.List.of(
            "Ofsted or regulatory inspection",
            "Internal safeguarding review",
            "Serious case / practice review",
            "Transfer to a placing local authority",
            "Legal proceedings");
}

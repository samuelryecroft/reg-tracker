package ninja.samryecroft.returnhome.tracker.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryEntry;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistorySection;
import org.junit.jupiter.api.Test;

class AuditQueryCsvWriterTest {

    private final AuditQueryCsvWriter writer = new AuditQueryCsvWriter();

    private AuditHistoryEntry entry(String headline, String role, String detail) {
        return new AuditHistoryEntry(headline, LocalDateTime.of(2026, 8, 3, 14, 30), "14:30", role, detail, "ok");
    }

    private String csv(List<AuditHistorySection> sections) {
        return new String(writer.write(sections), StandardCharsets.UTF_8);
    }

    @Test
    void writesOneRowPerEntryUnderItsSection() {
        String output = csv(List.of(new AuditHistorySection("Request #1182 — Aug 2026",
                List.of(entry("Report approved", "Reviewer", "Submitted → Approved"),
                        entry("Report submitted for review", "Visitor", null)))));

        assertThat(output.lines()).hasSize(3);
        assertThat(output).contains("\"Request #1182 - Aug 2026\"".replace(" - ", " — "));
        assertThat(output).contains("\"Report approved\",\"Reviewer\"");
        assertThat(writer.rowCount(List.of(new AuditHistorySection("s",
                List.of(entry("a", "b", null), entry("c", "d", null)))))).isEqualTo(2);
    }

    @Test
    void namesNobody() {
        String output = csv(List.of(new AuditHistorySection("Request #1182",
                List.of(entry("Report approved", "Reviewer", null)))));

        // Role only, until the DPO answers D-1. The named variant is a projection flag on an entry
        // that already carries actorRole - a fast-follow, not a rebuild.
        assertThat(output).contains("Reviewer");
        assertThat(output).doesNotContain("@");
    }

    @Test
    void neutralisesValuesThatWouldExecuteAsSpreadsheetFormulas() {
        String output = csv(List.of(new AuditHistorySection("=cmd|'/c calc'!A1",
                List.of(entry("+1234", "Reviewer", "@SUM(A1:A9)")))));

        // Not theoretical: a leading =, +, - or @ is executed on open, which would turn an audit
        // export into a delivery mechanism. An audit CSV is exactly the file nobody inspects before
        // double-clicking it.
        assertThat(output).contains("\"'=cmd");
        assertThat(output).contains("\"'+1234\"");
        assertThat(output).contains("\"'@SUM(A1:A9)\"");
    }

    @Test
    void escapesQuotesRatherThanBreakingTheRow() {
        String output = csv(List.of(new AuditHistorySection("Section",
                List.of(entry("Report \"approved\"", "Reviewer", null)))));

        assertThat(output).contains("\"Report \"\"approved\"\"\"");
        // Still one header row plus one data row - the embedded quotes did not split the record.
        assertThat(output.lines()).hasSize(2);
    }

    @Test
    void nullDetailBecomesAnEmptyCellRatherThanTheWordNull() {
        assertThat(csv(List.of(new AuditHistorySection("Section",
                List.of(entry("Report approved", "Reviewer", null)))))).doesNotContain("null");
    }

    @Test
    void opensWithAByteOrderMarkSoExcelReadsItAsUtf8() {
        // Without it the em-dashes in real section labels arrive as mojibake for the person
        // reviewing the export, which undermines confidence in the whole artefact.
        assertThat(csv(List.of())).startsWith("﻿");
    }

    @Test
    void anEmptyViewStillProducesAHeader() {
        String output = csv(List.of());

        assertThat(output).contains("Section,When,What happened,Role,Detail");
        assertThat(writer.rowCount(List.of())).isZero();
    }
}

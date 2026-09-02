package ninja.samryecroft.returnhome.tracker.export;

import java.nio.charset.StandardCharsets;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryEntry;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistorySection;
import org.springframework.stereotype.Component;

/**
 * Renders the audit view someone is looking at as a CSV.
 *
 * <p>The other half of "two formats matched to two readers": a case file is a narrative an inspector
 * reads, whereas a CSV is a spreadsheet - sortable, filterable, and easy to re-cut into something
 * misleading. That is precisely why this exports <em>the current view</em> and nothing wider. There
 * is no method here that takes a query instead of an already-scoped result.
 *
 * <p>Built from {@link AuditHistoryEntry}, never from raw audit rows, so the same allow-list that
 * stops a template reaching a free-text {@code metadata} field also governs what can be written into
 * a file that leaves the building. Roles only - no names - until the DPO decision on D-1 lands.
 */
@Component
public class AuditQueryCsvWriter {

    private static final String SECTION_HEADER = "Section,When,What happened,Role,Detail";
    private static final String FEED_HEADER = "Home,Child,Interview,When,What happened,Role,Detail";

    public byte[] write(List<AuditHistorySection> sections) {
        StringBuilder csv = new StringBuilder();
        // A BOM, because these land in Excel more often than anywhere else and without it the
        // em-dashes and names in the content render as mojibake for the person reviewing them.
        csv.append('﻿').append(SECTION_HEADER).append("\r\n");
        for (AuditHistorySection section : sections) {
            for (AuditHistoryEntry entry : section.entries()) {
                csv.append(quote(section.label())).append(',')
                        .append(quote(entry.when())).append(',')
                        .append(quote(entry.headline())).append(',')
                        .append(quote(entry.actorRole())).append(',')
                        .append(quote(entry.detail())).append("\r\n");
            }
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * One row per entry of the org-wide case-activity feed.
     *
     * <p>This is the shape the audit-query export actually needs. The feed spans several children
     * and homes, so a flat table with those columns is what a reviewer can sort - the grouped form
     * above suits a single record's timeline and loses its meaning once the rows are mixed.
     *
     * <p>Still built from {@link AuditHistoryEntry} underneath, so the allow-list that keeps
     * free-text metadata out of the artefact governs this route too.
     */
    public byte[] writeFeed(List<FeedRow> rows) {
        StringBuilder csv = new StringBuilder();
        csv.append('﻿').append(FEED_HEADER).append("\r\n");
        for (FeedRow row : rows) {
            csv.append(quote(row.homeName())).append(',')
                    .append(quote(row.childLabel())).append(',')
                    .append(quote(row.requestId() == null ? null : "#" + row.requestId())).append(',')
                    .append(quote(row.entry().when())).append(',')
                    .append(quote(row.entry().headline())).append(',')
                    .append(quote(row.entry().actorRole())).append(',')
                    .append(quote(row.entry().detail())).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Deliberately declared here rather than taking the feed's own row type: this keeps the export
     * package free of a dependency on the audit feed's view model, so the two can move
     * independently. Callers map their row to this in one line.
     */
    public record FeedRow(AuditHistoryEntry entry, String homeName, String childLabel, Long requestId) {
    }

    public int rowCount(List<AuditHistorySection> sections) {
        return sections.stream().mapToInt(section -> section.entries().size()).sum();
    }

    /**
     * Always quoted, and a leading formula character is neutralised.
     *
     * <p>The second part is not theoretical: a value beginning {@code =}, {@code +}, {@code -} or
     * {@code @} is executed as a formula when the file is opened, which turns an audit export into a
     * delivery mechanism. Our own content should never start that way, but a CSV of safeguarding
     * activity is exactly the file nobody scrutinises before double-clicking.
     */
    private String quote(String value) {
        if (value == null || value.isBlank()) {
            return "\"\"";
        }
        String safe = value;
        if ("=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}

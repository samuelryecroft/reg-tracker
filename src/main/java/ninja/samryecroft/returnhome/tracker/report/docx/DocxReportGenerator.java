package ninja.samryecroft.returnhome.tracker.report.docx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import ninja.samryecroft.returnhome.tracker.theme.ThemeService;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.springframework.stereotype.Component;

/**
 * Fills {@code ${token}} placeholders in a .docx template. Handles two POI-specific pitfalls:
 * placeholders whose text is split across multiple runs (Word/POI routinely splits a paragraph's
 * visible text into several XWPFRun objects), and multi-line values, where a literal '\n' does not
 * render as a line break in Word and must become an explicit run break instead.
 *
 * <p>Also resolves brand-colour tokens baked into the template's cell shading
 * ({@code w:fill="ACCENTFILLTOKEN"} etc.) — these live in XML attributes, not run text, so they're
 * substituted as a raw string replace over {@code word/document.xml} before POI ever parses the
 * template, letting the generated report pick up whatever colours are currently configured rather
 * than a colour baked into the .docx at authoring time.
 */
@Component
public class DocxReportGenerator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)}");
    private static final String MISSING_VALUE = "Not provided";
    /** Kept in step with ReportService's own placeholder for unanswered values. */
    private static final String NOT_RECORDED_VALUE = "Not recorded";
    private static final String MUTED_TEXT_COLOR = "686F7D";
    private static final String DOCUMENT_LANGUAGE = "en-GB";

    private static final String ACCENT_TOKEN = "ACCENTFILLTOKEN";
    /** Renamed from ACCENTDARKFILLTOKEN: this is a {@code w:color} (the title's TEXT), never a fill. */
    private static final String ACCENT_TEXT_DARK_TOKEN = "ACCENTTEXTDARKTOKEN";
    private static final String TINT_TOKEN = "TINTFILLTOKEN";
    /** Text sitting ON the accent (section-header bars) and ON the tint (label cells). */
    private static final String ACCENT_TEXT_TOKEN = "ACCENTTEXTTOKEN";
    private static final String TINT_TEXT_TOKEN = "TINTTEXTTOKEN";
    private static final String DEFAULT_ACCENT = "F36E2A";
    private static final String DEFAULT_ACCENT_DARK = "D85A1D";
    private static final String DEFAULT_TINT = "FFF0DD";
    private static final String DOCUMENT_XML_ENTRY = "word/document.xml";

    private static final String DEFAULT_ACCENT_TEXT = "1F2328";

    public void generate(InputStream templateStream, Map<String, String> values, Path outputPath) throws IOException {
        generate(templateStream, values, null, null, null, outputPath);
    }

    /**
     * @param accentColor     hex colour without a leading '#', e.g. {@code "F36E2A"}; null uses the built-in default
     * @param accentColorDark hex colour without a leading '#'; null uses the built-in default
     * @param tintColor       hex colour without a leading '#'; null uses the built-in default
     */
    public void generate(InputStream templateStream, Map<String, String> values,
            String accentColor, String accentColorDark, String tintColor, Path outputPath) throws IOException {
        generate(templateStream, values, accentColor, accentColorDark, tintColor, outputPath,
                RowCollapse.none());
    }

    /** The file-writing variant, for the generator's own tests, which need a document to reopen. */
    public void generate(InputStream templateStream, Map<String, String> values,
            String accentColor, String accentColorDark, String tintColor, Path outputPath,
            RowCollapse collapse) throws IOException {
        byte[] generated =
                generate(templateStream, values, accentColor, accentColorDark, tintColor, collapse);
        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, generated);
    }

    /**
     * Renders the document to bytes rather than a file.
     *
     * <p>This is the variant the application uses: the report is encrypted before it reaches
     * storage, so it must never touch a filesystem in plaintext on the way there. The {@link Path}
     * overload remains for the generator's own tests, which need a file to open with POI.
     */
    public byte[] generate(InputStream templateStream, Map<String, String> values,
            String accentColor, String accentColorDark, String tintColor) throws IOException {
        return generate(templateStream, values, accentColor, accentColorDark, tintColor,
                RowCollapse.none());
    }

    /**
     * Questions that were never asked are removed from the document rather than printed empty, and
     * one statement takes their place (T244).
     *
     * <p><b>Why the instruction is a parameter and not another entry in {@code values}.</b> That map
     * is the substitution channel, and T228 was one key read by two consumers, corrected for one of
     * them: a sentence meant for a table row became the document's core title. A value that changes
     * the document's STRUCTURE is not a substitution, and putting it there would invite the same
     * collapse.
     */
    public byte[] generate(InputStream templateStream, Map<String, String> values,
            String accentColor, String accentColorDark, String tintColor, RowCollapse collapse)
            throws IOException {
        String accent = stripHash(accentColor, DEFAULT_ACCENT);
        String tint = stripHash(tintColor, DEFAULT_TINT);
        byte[] templateBytes = applyBrandColors(templateBytesOf(templateStream), accent,
                stripHash(accentColorDark, DEFAULT_ACCENT_DARK), tint,
                // D-01: the fill IS the supplier's colour, so the text on it has to be a rule, not a
                // value. Shared with the UI button via ThemeService - deliberately not reimplemented.
                stripHash(ThemeService.readableForegroundOn(accent), DEFAULT_ACCENT_TEXT),
                stripHash(ThemeService.readableForegroundOn(tint), DEFAULT_ACCENT_TEXT));

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(templateBytes))) {
            // BEFORE substitution: the rows are identified by the ${tokens} still in them, which
            // is what makes the rule derivable from the question model rather than from row indices
            // in a binary file nobody can diff.
            collapseUnaskedRows(document, collapse);
            document.getParagraphs().forEach(paragraph -> substitute(paragraph, values));
            for (XWPFTable table : document.getTables()) {
                processTable(table, values);
            }
            // The footer carries the child identifier for continuation pages, and POI does not
            // include footer paragraphs in document.getParagraphs().
            document.getFooterList().forEach(footer -> {
                footer.getParagraphs().forEach(paragraph -> substitute(paragraph, values));
                footer.getTables().forEach(table -> processTable(table, values));
            });
            applyDocumentProperties(document, values);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Which question rows to remove, and the sentence that replaces them.
     *
     * @param placeholders the {@code ${token}} names whose rows go, taken from the question model so
     *     that adding a child's question does not require anyone to remember this file exists
     * @param statement    said ONCE, in place of the first removed question. Nine "not applicable"
     *     rows would distribute the significant fact - that a missing child was not spoken to -
     *     across nine lines and bury it, and a court or IRO reading nine of them learns nothing nine
     *     times.
     */
    public record RowCollapse(java.util.Set<String> placeholders, String statement) {
        public static RowCollapse none() {
            return new RowCollapse(java.util.Set.of(), "");
        }
    }

    /**
     * Removes each named question's row and the label row above it, and rewrites the FIRST of those
     * label rows to carry the statement.
     *
     * <p>Reusing a row the template author already made is deliberate: building a {@code <w:tr>} by
     * hand would mean reproducing this table's borders, widths and shading from memory, and getting
     * any of it wrong produces a malformed statutory document that still opens. The surviving row
     * keeps whatever styling the template gives it.
     */
    private void collapseUnaskedRows(XWPFDocument document, RowCollapse collapse) {
        if (collapse.placeholders().isEmpty()) {
            return;
        }
        for (XWPFTable table : document.getTables()) {
            List<Integer> doomed = new java.util.ArrayList<>();
            Integer statementRow = null;
            for (int i = 0; i < table.getRows().size(); i++) {
                if (!namesAny(table.getRow(i), collapse.placeholders())) {
                    continue;
                }
                doomed.add(i);
                // The label sits in its own row immediately above the answer.
                if (i > 0) {
                    if (statementRow == null) {
                        statementRow = i - 1;
                    } else {
                        doomed.add(i - 1);
                    }
                }
            }
            if (statementRow != null) {
                replaceText(table.getRow(statementRow), collapse.statement());
            }
            // distinct() before removing, and it is structural rather than a fix for today.
            // If two token-bearing rows were ever ADJACENT the loop adds i and then re-adds i - 1
            // as the next row's label; removal runs in reverse, so a repeated index deletes the row
            // that has SHIFTED INTO THAT SLOT - an innocent row silently gone from a statutory
            // document. It cannot fire against the current template, which is exactly why it needs
            // to be impossible rather than merely unobserved.
            doomed.stream().distinct().sorted(java.util.Comparator.reverseOrder())
                    .forEach(table::removeRow);
        }
    }

    private boolean namesAny(XWPFTableRow row, java.util.Set<String> placeholders) {
        String text = row.getTableCells().stream().map(XWPFTableCell::getText)
                .reduce("", (a, b) -> a + b);
        return placeholders.stream().anyMatch(name -> text.contains("${" + name + "}"));
    }

    /**
     * The statement in the first cell; the rest emptied, so no stray label survives beside it.
     *
     * <p><b>The replaced run keeps the original's character formatting.</b> These label runs carry
     * their bold, colour and size ON THE RUN, with no paragraph style to fall back on, so a bare
     * {@code createRun()} would render the sentence in the document default among 9pt bold grey
     * labels. Reusing the template author's ROW keeps the borders, widths and shading; it does not
     * keep run properties, and the earlier claim that this "keeps whatever styling the template
     * gives it" was true at row and cell level and false at the level that decides how the sentence
     * actually looks.
     *
     * <p>It matters more here than formatting usually does: this is a statutory document, and the
     * sentence is the one thing in it a reader must not mistake for a stray note.
     */
    private void replaceText(XWPFTableRow row, String statement) {
        List<XWPFTableCell> cells = row.getTableCells();
        for (int c = 0; c < cells.size(); c++) {
            XWPFTableCell cell = cells.get(c);
            CTRPr formatting = firstRunFormatting(cell);
            for (XWPFParagraph paragraph : cell.getParagraphs()) {
                for (int r = paragraph.getRuns().size() - 1; r >= 0; r--) {
                    paragraph.removeRun(r);
                }
            }
            if (c == 0 && !cell.getParagraphs().isEmpty()) {
                XWPFRun run = cell.getParagraphs().get(0).createRun();
                if (formatting != null) {
                    run.getCTR().setRPr(formatting);
                }
                run.setText(statement);
            }
        }
    }

    /** A detached copy - the run it came from is about to be deleted, so a reference would dangle. */
    private CTRPr firstRunFormatting(XWPFTableCell cell) {
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            for (XWPFRun run : paragraph.getRuns()) {
                if (run.getCTR().getRPr() != null) {
                    return (CTRPr) run.getCTR().getRPr().copy();
                }
            }
        }
        return null;
    }

    private String stripHash(String color, String fallback) {
        if (color == null || color.isBlank()) {
            return fallback;
        }
        return color.startsWith("#") ? color.substring(1) : color;
    }

    private byte[] templateBytesOf(InputStream templateStream) throws IOException {
        return templateStream.readAllBytes();
    }

    private byte[] applyBrandColors(byte[] templateBytes, String accentColor, String accentColorDark,
            String tintColor, String accentTextColor, String tintTextColor) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(templateBytes));
                ZipOutputStream zout = new ZipOutputStream(buffer)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                byte[] data = zin.readAllBytes();
                if (DOCUMENT_XML_ENTRY.equals(entry.getName())) {
                    // ACCENT_TEXT_DARK_TOKEN before ACCENT_TOKEN would be wrong the other way round:
                    // the longer token names are replaced first so a shorter one cannot match inside them.
                    String xml = new String(data, StandardCharsets.UTF_8)
                            .replace(ACCENT_TEXT_DARK_TOKEN, accentColorDark)
                            .replace(ACCENT_TEXT_TOKEN, accentTextColor)
                            .replace(TINT_TEXT_TOKEN, tintTextColor)
                            .replace(ACCENT_TOKEN, accentColor)
                            .replace(TINT_TOKEN, tintColor);
                    data = xml.getBytes(StandardCharsets.UTF_8);
                }
                zout.putNextEntry(new ZipEntry(entry.getName()));
                zout.write(data);
                zout.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private void processTable(XWPFTable table, Map<String, String> values) {
        for (var row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                cell.getParagraphs().forEach(paragraph -> substitute(paragraph, values));
                for (XWPFTable nested : cell.getTables()) {
                    processTable(nested, values);
                }
            }
        }
    }

    /**
     * D-07. The title is what Word shows in Recent Files and what a PDF conversion adopts as its
     * document title; the language is what a screen reader uses to choose pronunciation. Creator was
     * literally "Apache POI".
     *
     * <p><b>T228: the date here comes from {@code titleDate}, not from {@code interviewDate}.</b>
     * They were one key, and when that key became a sentence describing the 72-hour reading, the
     * title silently became one too - a report with no recorded time was NAMED "... - Interview time
     * not recorded". The title is the last place a data gap should be able to reach, because it is
     * read before the document is opened and by people deciding whether to open it.
     *
     * <p>The lesson is not "pick a safer shared string": a value read by two consumers with
     * different needs will eventually be corrected for one of them. {@code titleDate} exists so this
     * one cannot be.
     */
    private void applyDocumentProperties(XWPFDocument document, Map<String, String> values) {
        String child = values.getOrDefault("childName", "Unknown");
        String titleDate = values.getOrDefault("titleDate", "");
        POIXMLProperties properties = document.getProperties();
        properties.getCoreProperties().setTitle(
                "Return Home Interview Report - " + child + (titleDate.isBlank() ? "" : " - " + titleDate));
        properties.getCoreProperties().setSubjectProperty("Return Home Interview");
        properties.getCoreProperties().setCreator(values.getOrDefault("supplierName", "Return Home Tracker"));
        properties.getCoreProperties().getUnderlyingProperties().setLanguageProperty(DOCUMENT_LANGUAGE);
    }

    /**
     * D-10. An unanswered question renders in italic muted grey so a reader can tell at a glance
     * that a page is largely empty rather than substantively completed.
     *
     * <p>This has to be applied in code: {@link #substitute} collapses each paragraph to a single
     * run, which drops any inline formatting the template might have carried for these.
     */
    private boolean isEmptyValue(String resolved) {
        return MISSING_VALUE.equals(resolved) || NOT_RECORDED_VALUE.equals(resolved);
    }

    private void substitute(XWPFParagraph paragraph, Map<String, String> values) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.isEmpty()) {
            return;
        }

        StringBuilder full = new StringBuilder();
        for (XWPFRun run : runs) {
            String text = run.getText(0);
            full.append(text == null ? "" : text);
        }
        String original = full.toString();
        if (!original.contains("${")) {
            return;
        }

        Matcher matcher = PLACEHOLDER.matcher(original);
        StringBuilder resolved = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            resolved.append(original, last, matcher.start());
            String key = matcher.group(1);
            resolved.append(values.getOrDefault(key, MISSING_VALUE));
            last = matcher.end();
        }
        resolved.append(original.substring(last));

        // Collapse to a single run. This deliberately drops any inline formatting boundaries
        // that existed within the paragraph, on the assumption (enforced by the template's
        // authoring convention) that a placeholder is the sole content of its paragraph/cell.
        for (int i = runs.size() - 1; i >= 1; i--) {
            paragraph.removeRun(i);
        }
        XWPFRun run0 = runs.get(0);

        String resolvedText = resolved.toString();
        if (isEmptyValue(resolvedText.trim())) {
            run0.setItalic(true);
            run0.setColor(MUTED_TEXT_COLOR);
        }

        String[] lines = resolvedText.split("\n", -1);
        run0.setText(lines[0], 0);
        for (int i = 1; i < lines.length; i++) {
            run0.addBreak();
            run0.setText(lines[i]);
        }
    }
}

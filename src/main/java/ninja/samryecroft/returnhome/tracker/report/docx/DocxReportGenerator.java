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
        String accent = stripHash(accentColor, DEFAULT_ACCENT);
        String tint = stripHash(tintColor, DEFAULT_TINT);
        byte[] templateBytes = applyBrandColors(templateBytesOf(templateStream), accent,
                stripHash(accentColorDark, DEFAULT_ACCENT_DARK), tint,
                // D-01: the fill IS the supplier's colour, so the text on it has to be a rule, not a
                // value. Shared with the UI button via ThemeService - deliberately not reimplemented.
                stripHash(ThemeService.readableForegroundOn(accent), DEFAULT_ACCENT_TEXT),
                stripHash(ThemeService.readableForegroundOn(tint), DEFAULT_ACCENT_TEXT));

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(templateBytes))) {
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

            Files.createDirectories(outputPath.getParent());
            try (OutputStream out = Files.newOutputStream(outputPath)) {
                document.write(out);
            }
        }
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
     */
    private void applyDocumentProperties(XWPFDocument document, Map<String, String> values) {
        String child = values.getOrDefault("childName", "Unknown");
        String interviewDate = values.getOrDefault("interviewDate", "");
        POIXMLProperties properties = document.getProperties();
        properties.getCoreProperties().setTitle(
                "Return Home Interview Report - " + child + (interviewDate.isBlank() ? "" : " - " + interviewDate));
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

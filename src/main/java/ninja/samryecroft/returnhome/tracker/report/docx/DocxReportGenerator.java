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

    private static final String ACCENT_TOKEN = "ACCENTFILLTOKEN";
    private static final String ACCENT_DARK_TOKEN = "ACCENTDARKFILLTOKEN";
    private static final String TINT_TOKEN = "TINTFILLTOKEN";
    private static final String DEFAULT_ACCENT = "F36E2A";
    private static final String DEFAULT_ACCENT_DARK = "D85A1D";
    private static final String DEFAULT_TINT = "FFF0DD";
    private static final String DOCUMENT_XML_ENTRY = "word/document.xml";

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
        byte[] templateBytes = applyBrandColors(templateStream.readAllBytes(),
                stripHash(accentColor, DEFAULT_ACCENT), stripHash(accentColorDark, DEFAULT_ACCENT_DARK),
                stripHash(tintColor, DEFAULT_TINT));

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(templateBytes))) {
            document.getParagraphs().forEach(paragraph -> substitute(paragraph, values));
            for (XWPFTable table : document.getTables()) {
                processTable(table, values);
            }

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

    private byte[] applyBrandColors(byte[] templateBytes, String accentColor, String accentColorDark, String tintColor)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(templateBytes));
                ZipOutputStream zout = new ZipOutputStream(buffer)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                byte[] data = zin.readAllBytes();
                if (DOCUMENT_XML_ENTRY.equals(entry.getName())) {
                    String xml = new String(data, StandardCharsets.UTF_8)
                            .replace(ACCENT_TOKEN, accentColor)
                            .replace(ACCENT_DARK_TOKEN, accentColorDark)
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

        String[] lines = resolved.toString().split("\n", -1);
        run0.setText(lines[0], 0);
        for (int i = 1; i < lines.length; i++) {
            run0.addBreak();
            run0.setText(lines[i]);
        }
    }
}

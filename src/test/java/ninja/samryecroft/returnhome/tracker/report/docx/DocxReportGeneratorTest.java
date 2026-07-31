package ninja.samryecroft.returnhome.tracker.report.docx;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

class DocxReportGeneratorTest {

    private final DocxReportGenerator generator = new DocxReportGenerator();

    @Test
    void resolvesAllPlaceholdersAndConvertsNewlinesToRealLineBreaks(@TempDir Path tempDir) throws Exception {
        Map<String, String> values = Map.ofEntries(
                Map.entry("childName", "Alex Smith"),
                Map.entry("homeName", "Oakwood House"),
                Map.entry("visitorName", "Visitor Chris"),
                Map.entry("requestReceivedAt", "16 Jul 2026 20:30"),
                Map.entry("missingEpisodeDate", "15 Jul 2026 18:00"),
                Map.entry("interviewDate", "20 Jul 2026"),
                Map.entry("interviewLocation", "Oakwood House, living room"),
                Map.entry("within72Hours", "Yes"),
                Map.entry("ifNotWhyLate", "Not provided"),
                Map.entry("consultationWithHomeStaff", "Spoke with key worker on arrival"),
                Map.entry("previouslyMissing", "Yes"),
                Map.entry("missingOccasionsLast30Days", "2"),
                Map.entry("confidentialityExplained", "Yes"),
                Map.entry("interviewAccepted", "Yes"),
                Map.entry("interviewDeclinedReason", "Not provided"),
                Map.entry("whereWereYouWhileMissing", "At a friend's house"),
                Map.entry("whoWereYouWithWhileMissing", "A friend from school"),
                Map.entry("whatMadeYouGoMissing", "Conflict with a peer"),
                Map.entry("whatCanBeDoneToAddressReasons", "Mediation with the peer"),
                Map.entry("consideredSelfMissing", "No"),
                Map.entry("whatDidYouDoWhileMissing", "Line one of the account.\nLine two of the account.\nLine three."),
                Map.entry("whatHappenedWhenReturned", "Welcomed back and had supper"),
                Map.entry("preventFutureMissingSuggestions", "Regular check-ins"),
                Map.entry("additionalCommentsFromYoungPerson", "Feels settled"),
                Map.entry("additionalInfoFromParentCarer", "Not provided"),
                Map.entry("risksIdentifiedDuringEpisode", "None identified"),
                Map.entry("risksIncreaseFutureEpisodes", "Not provided"),
                Map.entry("safeguardingConcernsToExplore", "Not provided"),
                Map.entry("infoToHelpLocateFuture", "Not provided"),
                Map.entry("interviewerComments", "Cooperative throughout"),
                Map.entry("recommendations", "No further action"),
                Map.entry("conductedByStatement", "Conducted by Visitor Chris"),
                Map.entry("signedLine", "Signed electronically by Visitor Chris on 20 Jul 2026 15:00"),
                Map.entry("dateReportShared", "Not yet shared"),
                Map.entry("generatedAt", "18 Jul 2026 00:00"));

        Path outputPath = tempDir.resolve("generated.docx");

        try (InputStream templateStream = new ClassPathResource("docx-templates/rhi-report-template.docx").getInputStream()) {
            generator.generate(templateStream, values, outputPath);
        }

        assertThat(outputPath).exists();

        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(outputPath))) {
            String allText = extractAllText(document);

            assertThat(allText).doesNotContain("${");
            assertThat(allText).contains("Alex Smith");
            assertThat(allText).contains("Oakwood House");
            assertThat(allText).contains("Line one of the account.");
            assertThat(allText).contains("Line three.");

            boolean anyRunHasBreak = document.getTables().stream()
                    .flatMap(t -> t.getRows().stream())
                    .flatMap(r -> r.getTableCells().stream())
                    .flatMap(c -> c.getParagraphs().stream())
                    .flatMap(p -> p.getRuns().stream())
                    .anyMatch(run -> run.getCTR().sizeOfBrArray() > 0);
            assertThat(anyRunHasBreak).as("expected at least one run to contain a <w:br/> from the multi-line value").isTrue();
        }
    }

    @Test
    void fillsInPlaceholderForMissingValue(@TempDir Path tempDir) throws Exception {
        Path outputPath = tempDir.resolve("generated-missing.docx");

        try (InputStream templateStream = new ClassPathResource("docx-templates/rhi-report-template.docx").getInputStream()) {
            generator.generate(templateStream, Map.of(), outputPath);
        }

        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(outputPath))) {
            String allText = extractAllText(document);
            assertThat(allText).doesNotContain("${");
            assertThat(allText).contains("Not provided");
        }
    }

    @Test
    void resolvesBrandColourTokensIntoCellShading(@TempDir Path tempDir) throws Exception {
        Path outputPath = tempDir.resolve("generated-branded.docx");

        try (InputStream templateStream = new ClassPathResource("docx-templates/rhi-report-template.docx").getInputStream()) {
            generator.generate(templateStream, Map.of(), "#7C3AED", "#5B21B6", "#EDE9FE", outputPath);
        }

        String documentXml = readDocumentXml(outputPath);
        assertThat(documentXml).doesNotContain("FILLTOKEN");
        assertThat(documentXml).contains("7C3AED");
        assertThat(documentXml).contains("5B21B6");
        assertThat(documentXml).contains("EDE9FE");
    }

    private String readDocumentXml(Path docxPath) throws Exception {
        try (var zip = new java.util.zip.ZipFile(docxPath.toFile())) {
            var entry = zip.getEntry("word/document.xml");
            try (var in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }

    private String extractAllText(XWPFDocument document) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            sb.append(paragraph.getText()).append('\n');
        }
        for (XWPFTable table : document.getTables()) {
            for (var row : table.getRows()) {
                for (var cell : row.getTableCells()) {
                    sb.append(cell.getText()).append('\n');
                }
            }
        }
        return sb.toString();
    }
}

package ninja.samryecroft.returnhome.tracker.report.docx;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

class DocxReportGeneratorTest {

    private final DocxReportGenerator generator = new DocxReportGenerator();

    /** Reads a named part straight out of the generated package. */
    private String partOf(Path docx, String entryName) throws Exception {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(docx.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry(entryName);
            assertThat(entry).as("package contains %s", entryName).isNotNull();
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }

    private Path generateWithBrand(Path tempDir, String name, String accent, String tint) throws Exception {
        Path outputPath = tempDir.resolve(name);
        try (InputStream templateStream =
                new ClassPathResource("docx-templates/rhi-report-template.docx").getInputStream()) {
            generator.generate(templateStream,
                    Map.of("childName", "Alex Smith", "caseReference", "CR-42",
                            // The body row and the document's NAME are two values now (T228).
                            // interviewDate is the reading's own sentence and can say things like
                            // "Interview time not recorded"; titleDate is a date or nothing.
                            "interviewDate", "20 Jul 2026 14:05", "titleDate", "20 Jul 2026",
                            "supplierName", "STEPS with Children",
                            "interviewerComments", "Not provided"),
                    accent, null, tint, outputPath);
        }
        return outputPath;
    }

    @Test
    void headerBarTextColourIsChosenByContrastAgainstTheSupplierAccent(@TempDir Path tempDir) throws Exception {
        // D-01. The shipped palette that measured 1.98:1 with the old baked-in white.
        String paleAccent = partOf(generateWithBrand(tempDir, "pale.docx", "f4aa2a", "FFF0DD"),
                "word/document.xml");
        // Ink wins on a pale accent...
        assertThat(paleAccent).contains("<w:color w:val=\"1F2328\"/>");
        assertThat(paleAccent).doesNotContain("ACCENTTEXTTOKEN");
        // ...and no white header text survives anywhere.
        assertThat(paleAccent).doesNotContain("<w:color w:val=\"FFFFFF\"/>");

        // A dark accent must flip the other way.
        String darkAccent = partOf(generateWithBrand(tempDir, "dark.docx", "1D4ED8", "1D4ED8"),
                "word/document.xml");
        assertThat(darkAccent).contains("<w:color w:val=\"FFFFFF\"/>");

        // The cell FILLS the contrast rule applies to are a different thing entirely and must
        // survive both. T98 D-3 removed the two-tone stripe - 34 tinted label cells against 34
        // explicitly white value cells - so the tint now appears exactly six times, once per
        // section band, and that band is what the colour above is chosen to be readable on.
        assertThat(paleAccent.split("w:fill=\"FFF0DD\"", -1).length - 1)
                .as("one tinted section band per section, and nothing else filled")
                .isEqualTo(6);
        assertThat(darkAccent.split("w:fill=\"1D4ED8\"", -1).length - 1).isEqualTo(6);
        // The striped grid itself is gone, in both directions.
        assertThat(paleAccent).doesNotContain("w:fill=\"FFFFFF\"");
        assertThat(darkAccent).doesNotContain("w:fill=\"FFFFFF\"");
    }

    @Test
    void everyBrandTokenIsResolvedLeavingNoPlaceholderBehind(@TempDir Path tempDir) throws Exception {
        String xml = partOf(generateWithBrand(tempDir, "tokens.docx", "F36E2A", "FFF0DD"), "word/document.xml");

        // D-08: the old misleading name is gone, and nothing token-shaped is left in the output.
        for (String token : new String[] {"ACCENTFILLTOKEN", "ACCENTDARKFILLTOKEN", "ACCENTTEXTDARKTOKEN",
                "TINTFILLTOKEN", "ACCENTTEXTTOKEN", "TINTTEXTTOKEN"}) {
            assertThat(xml).as("token %s resolved", token).doesNotContain(token);
        }
    }

    @Test
    void documentHasHeadingStructurePageSetupAndAFooter(@TempDir Path tempDir) throws Exception {
        Path docx = generateWithBrand(tempDir, "structure.docx", "F36E2A", "FFF0DD");
        String document = partOf(docx, "word/document.xml");

        // D-03: real heading styles, not bold-and-big direct formatting.
        String styles = partOf(docx, "word/styles.xml");
        assertThat(styles).contains("w:styleId=\"Heading1\"").contains("w:styleId=\"Title\"");
        assertThat(document).contains("<w:pStyle w:val=\"Title\"/>");
        assertThat(document.split("<w:pStyle w:val=\"Heading1\"/>", -1).length - 1).isEqualTo(6);

        // D-04: explicit UK A4 so pagination can't depend on the reader's Word locale.
        assertThat(document).contains("<w:sectPr>").contains("w:w=\"11906\"").contains("w:h=\"16838\"");
        assertThat(document).contains("w:top=\"1134\"");

        // D-05: page numbers plus the child identifier on continuation pages.
        String footer = partOf(docx, "word/footer1.xml");
        assertThat(footer).contains("PAGE").contains("NUMPAGES");
        assertThat(footer).contains("Alex Smith").contains("CR-42");
        assertThat(footer).doesNotContain("${");

        // D-06 / T98 Q-1: an explicit font rather than whatever Word defaults to - now Aptos,
        // with Calibri named as its fallback in the font table rather than embedded, because the
        // file is exported and emailed and has to render on someone else's machine.
        assertThat(styles).contains("w:ascii=\"Aptos\"");
        String fontTable = partOf(docx, "word/fontTable.xml");
        assertThat(fontTable).contains("w:name=\"Aptos\"").contains("<w:altName w:val=\"Calibri\"/>");
    }

    @Test
    void everyRowSpansTheFullFixedGridAndEveryStackedAnswerHoldsTheSameMeasure(@TempDir Path tempDir)
            throws Exception {
        // T98. This is the whole redesign in one assertion, and Creed asked for it as a permanent
        // check rather than a one-off: the 45mm/125mm grid only holds if EVERY table and EVERY row
        // adds up to the content width, and the answer measure is only "one measure everywhere" if
        // every stacked answer is inset by exactly one label column. Both would rot silently the
        // first time someone hand-edits a row into the template.
        String xml = partOf(generateWithBrand(tempDir, "grid.docx", "F36E2A", "FFF0DD"),
                "word/document.xml");

        int tables = 0;
        for (String table : xml.split("<w:tbl>")) {
            if (!table.contains("<w:tblGrid>")) {
                continue; // the text before the first table
            }
            tables++;
            assertThat(sumOf("<w:gridCol w:w=\"(\\d+)\"", table))
                    .as("table %d columns span the 170mm content width", tables)
                    .isEqualTo(CONTENT_WIDTH_TWIPS);
            int rows = 0;
            for (String row : table.split("<w:tr>")) {
                if (!row.contains("<w:tcW ")) {
                    continue;
                }
                rows++;
                assertThat(sumOf("<w:tcW w:w=\"(\\d+)\"", row))
                        .as("table %d row %d spans the full grid", tables, rows)
                        .isEqualTo(CONTENT_WIDTH_TWIPS);
            }
            assertThat(rows).as("table %d has rows", tables).isPositive();
        }
        // Six section tables, the head block and the signature block.
        assertThat(tables).isEqualTo(8);

        // A stacked answer is a full-width cell inset on the right by exactly one label column, so
        // it reads at 125mm - identical to an inline answer in the 2551/7087 pair.
        int stackedAnswers = xml.split("<w:tcMar><w:right w:w=\"2551\" w:type=\"dxa\"/></w:tcMar>", -1)
                .length - 1;
        assertThat(stackedAnswers)
                .as("every stacked answer is inset by exactly one label column")
                .isEqualTo(22);
    }

    private static final int CONTENT_WIDTH_TWIPS = 9638;

    private int sumOf(String pattern, String xml) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(xml);
        int total = 0;
        while (m.find()) {
            total += Integer.parseInt(m.group(1));
        }
        return total;
    }

    @Test
    void documentPropertiesAreSet(@TempDir Path tempDir) throws Exception {
        Path docx = generateWithBrand(tempDir, "props.docx", "F36E2A", "FFF0DD");
        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(docx))) {
            var core = document.getProperties().getCoreProperties();
            // D-07: title, real creator, language - creator was literally "Apache POI".
            assertThat(core.getTitle()).contains("Return Home Interview Report").contains("Alex Smith");
            // T228: the title takes titleDate, never interviewDate. That key now holds the 72-hour
            // reading's own sentence, so a report with no recorded time would otherwise be NAMED
            // "... - Interview time not recorded" - what Word shows in Recent Files and what a PDF
            // conversion adopts, read by people deciding whether to open the document at all.
            assertThat(core.getTitle()).contains("20 Jul 2026").doesNotContain("14:05");
            assertThat(core.getCreator()).isEqualTo("STEPS with Children");
            assertThat(core.getCreator()).isNotEqualTo("Apache POI");
            assertThat(core.getUnderlyingProperties().getLanguageProperty()).contains("en-GB");
        }
    }

    /**
     * T228. The title must never become a sentence about a data gap.
     *
     * <p>It shared the {@code interviewDate} key with the body row until now. When T187 corrected
     * that row to carry the 72-hour reading, it corrected the title too - <b>one value, two
     * consumers, fixed for one of them</b> - and a report with no recorded time was named
     * "Return Home Interview Report - Alex Smith - Interview time not recorded".
     *
     * <p>Creed found it from the consumer end, and nothing in the diff that changed the row
     * mentioned the title: <b>a map key hides its second consumer better than a method signature
     * does.</b> So the fix is not a safer shared string but a second value, and this pins that the
     * title has no route back to the first one - a missing date shortens the name rather than
     * explaining itself.
     */
    @Test
    void aMissingInterviewTimeShortensTheTitleRatherThanNamingTheDocumentAfterTheGap(
            @TempDir Path tempDir) throws Exception {
        Path outputPath = tempDir.resolve("no-time.docx");
        try (InputStream templateStream =
                new ClassPathResource("docx-templates/rhi-report-template.docx").getInputStream()) {
            generator.generate(templateStream,
                    Map.of("childName", "Alex Smith",
                            "interviewDate", "Interview time not recorded",
                            "titleDate", "",
                            "interviewerComments", "Not provided"),
                    null, null, null, outputPath);
        }

        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(outputPath))) {
            String title = document.getProperties().getCoreProperties().getTitle();

            assertThat(title)
                    .as("the document is still named, and named after the child")
                    .isEqualTo("Return Home Interview Report - Alex Smith");
            assertThat(title)
                    .as("this string is a correct sentence in the body of the report and an "
                            + "indefensible one in its name - it is what a court or an IRO would see "
                            + "in a file list before opening anything")
                    .doesNotContain("Interview time not recorded")
                    .doesNotContain("not recorded");
        }
    }

    /**
     * T244: on a declined interview the child's questions leave the document and one statement takes
     * their place.
     *
     * <p><b>The document must not contradict the screen it was generated from.</b> The record view
     * collapses the same nine, so a .docx that printed nine empty rows would say the child was asked
     * nine questions and answered none, while the screen says they were never asked - and the
     * council reads the document.
     *
     * <p>Asserted on the document's actual TEXT rather than on the XML, because what matters is what
     * a reader sees. The parent or carer's question is checked to survive: it sits immediately after
     * the nine in the template, so an off-by-one in the row arithmetic would take it - and on a
     * declined interview it may be the only account of the episode anyone obtains.
     */
    @Test
    void aDeclinedInterviewRemovesTheChildsQuestionRowsAndStatesWhyOnce(@TempDir Path tempDir)
            throws Exception {
        String statement = "The young person was not interviewed, so these questions were not asked.";
        Path outputPath = tempDir.resolve("declined.docx");
        try (InputStream templateStream =
                new ClassPathResource("docx-templates/rhi-report-template.docx").getInputStream()) {
            generator.generate(templateStream,
                    Map.of("childName", "Alex Smith", "titleDate", "20 Jul 2026",
                            "interviewAccepted", "No",
                            "interviewDeclinedReason", "The young person declined",
                            "additionalInfoFromParentCarer", "Parent gave an account"),
                    null, null, null, outputPath,
                    new DocxReportGenerator.RowCollapse(
                            Set.of("whereWereYouWhileMissing", "whoWereYouWithWhileMissing",
                                    "whatMadeYouGoMissing", "whatCanBeDoneToAddressReasons",
                                    "consideredSelfMissing", "whatDidYouDoWhileMissing",
                                    "whatHappenedWhenReturned", "preventFutureMissingSuggestions",
                                    "additionalCommentsFromYoungPerson"),
                            statement));
        }

        String text = textOf(outputPath);

        assertThat(text)
                .as("said ONCE - nine 'not applicable' rows would bury the significant fact, which "
                        + "is that a missing child was not spoken to")
                .containsOnlyOnce(statement);
        for (String question : List.of("Where were you while missing?", "What made you go missing?",
                "Any additional comments from the young person?")) {
            assertThat(text)
                    .as("this was never asked, so it must not appear in the council's copy")
                    .doesNotContain(question);
        }
        assertThat(text)
                .as("NOT the child's answer, and the row immediately after the nine - an off-by-one "
                        + "in the row arithmetic deletes exactly this one")
                .contains("Any additional information provided by the parent/carer?")
                .contains("Parent gave an account");
        assertThat(text)
                .as("the visitor's own rows are untouched: the reason the interview did not happen "
                        + "is the thing the statement points at")
                .contains("If not, why?")
                .contains("The young person declined");
        assertThat(text)
                .as("no placeholder may survive a removal")
                .doesNotContain("${");
    }

    /**
     * The statement keeps the character formatting of the label row it replaces.
     *
     * <p>Those label runs carry their bold, colour and size <b>on the run</b>, with no paragraph
     * style to fall back on, so a bare {@code createRun()} renders the sentence in the document
     * default among 9pt bold grey labels. Reusing the template author's row keeps borders, widths
     * and shading - it does not keep run properties, and that is the level which decides how the
     * sentence actually looks.
     *
     * <p>Asserted because it is invisible to every other check here: the text is present and
     * correct either way, the document opens either way, and the only symptom is that the one
     * sentence a reader must not mistake for a stray note looks exactly like a stray note.
     */
    @Test
    void theStatementInheritsTheReplacedRowsRunFormatting(@TempDir Path tempDir) throws Exception {
        String statement = "The young person was not interviewed, so these questions were not asked.";
        Path outputPath = tempDir.resolve("formatting.docx");
        try (InputStream templateStream =
                new ClassPathResource("docx-templates/rhi-report-template.docx").getInputStream()) {
            generator.generate(templateStream, Map.of("childName", "Alex Smith"),
                    null, null, null, outputPath,
                    new DocxReportGenerator.RowCollapse(
                            Set.of("whereWereYouWhileMissing", "whoWereYouWithWhileMissing",
                                    "whatMadeYouGoMissing", "whatCanBeDoneToAddressReasons",
                                    "consideredSelfMissing", "whatDidYouDoWhileMissing",
                                    "whatHappenedWhenReturned", "preventFutureMissingSuggestions",
                                    "additionalCommentsFromYoungPerson"),
                            statement));
        }

        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(outputPath))) {
            XWPFRun statementRun = document.getTables().stream()
                    .flatMap(t -> t.getRows().stream())
                    .flatMap(r -> r.getTableCells().stream())
                    .flatMap(c -> c.getParagraphs().stream())
                    .flatMap(pr -> pr.getRuns().stream())
                    .filter(run -> statement.equals(run.getText(0)))
                    .findFirst()
                    .orElse(null);

            assertThat(statementRun).as("the statement must be in the document at all").isNotNull();
            assertThat(statementRun.getCTR().getRPr())
                    .as("a bare run inherits nothing, and these labels carry their formatting ON "
                            + "the run - so the sentence would render in the document default")
                    .isNotNull();
        }
    }

    /** Every table cell and paragraph, in document order - what a reader would actually see. */
    private String textOf(Path docx) throws Exception {
        StringBuilder text = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(docx))) {
            document.getParagraphs().forEach(p -> text.append(p.getText()).append('\n'));
            document.getTables().forEach(t -> t.getRows().forEach(r ->
                    r.getTableCells().forEach(c -> text.append(c.getText()).append('\n'))));
        }
        return text.toString();
    }

    @Test
    void unansweredValuesRenderMutedSoAnEmptyReportLooksEmpty(@TempDir Path tempDir) throws Exception {
        // D-10: 16 of these appeared in a shipped sample, styled identically to real answers.
        String xml = partOf(generateWithBrand(tempDir, "empty.docx", "F36E2A", "FFF0DD"), "word/document.xml");
        assertThat(xml).contains("686F7D");
        assertThat(xml).contains("Not provided");
    }

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

package ninja.samryecroft.returnhome.tracker.report.question;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * T185, step 1: the .docx template and the code that fills it must agree about what the report
 * contains - <b>in both directions</b>.
 *
 * <p><b>Why this is the one that most needed a guard.</b> An unwired placeholder does not blow up.
 * {@code DocxReportGenerator} substitutes it with {@code MISSING_VALUE = "Not provided"}, which then
 * picks up the deliberate D-10 <em>unanswered</em> formatting - so a wiring gap presents to whoever
 * reads the exported record as <b>"the child was asked this and did not answer"</b>. A statutory
 * document says somebody declined to speak, because of a typo. The reverse gap is quieter still: a
 * value with no placeholder is simply absent, and nothing anywhere reports it.
 *
 * <p>Neither direction was pinned before this. The list of what the export contains lives inside a
 * <b>binary artefact</b>: it is not greppable, it does not appear in a diff, and once the question
 * model became the trusted single source it would have been the one remaining copy nobody had a
 * reason to look at. That is the argument for a guard rather than the one-off reconciliation -
 * measured clean on 2026-09-05 by Kevin, and measured clean again here, which is a property that
 * holds rather than a defect fixed.
 *
 * <p><b>Read the header and footer parts, not just the document body.</b> A first manual pass over
 * {@code word/document.xml} alone reported two questions as missing from the template; that was an
 * extraction artefact - they are in {@code word/footer1.xml}. A truncated set comparison looks
 * exactly like a finding, which is the second argument for automating it.
 */
class ReportExportReconciliationTest {

    private static final Path TEMPLATE =
            Path.of("src/main/resources/docx-templates/rhi-report-template.docx");
    private static final Path REPORT_SERVICE = Path.of(
            "src/main/java/ninja/samryecroft/returnhome/tracker/report/ReportService.java");

    private static final Path DOCX_GENERATOR = Path.of(
            "src/main/java/ninja/samryecroft/returnhome/tracker/report/docx/DocxReportGenerator.java");

    /**
     * Values that are deliberately not {@code ${token}} substitutions: they are consumed as Word
     * <b>document properties</b> rather than rendered into the body.
     *
     * <ul>
     *   <li><b>titleDate</b> - the core Title's date (T228). It exists precisely because sharing the
     *       body row's {@code interviewDate} key made the title a second consumer of a value that
     *       was then corrected for the first one, and a report with no recorded time was NAMED
     *       "... - Interview time not recorded".</li>
     * </ul>
     *
     * <p>An allow-list rather than a filter, and one that is itself verified below: a key named here
     * must actually be read by {@code applyDocumentProperties}. Otherwise a typo in this set would
     * exempt a genuinely orphaned value - <b>an exception that is merely declared is a hole; one
     * that is checked is a rule.</b>
     */
    private static final Set<String> DOCUMENT_PROPERTIES = Set.of("titleDate");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)}");
    private static final Pattern VALUES_PUT = Pattern.compile("values\\.put\\(\"(\\w+)\"");

    @Test
    void everyTemplatePlaceholderIsFilledAndEveryFilledValueHasAPlaceholder() throws IOException {
        Set<String> placeholders = templatePlaceholders();
        Set<String> filled = valuesMapKeys();

        assertThat(placeholders)
                .as("no placeholders found - the extraction has broken and every assertion below "
                        + "passes for the wrong reason")
                .hasSizeGreaterThan(30);
        assertThat(filled)
                .as("no values.put(...) calls found - the extraction has broken")
                .hasSizeGreaterThan(30);

        assertThat(difference(placeholders, filled))
                .as("these ${tokens} are in the .docx template and nothing fills them. Each will "
                        + "render as \"Not provided\" with the unanswered styling, so the exported "
                        + "statutory record will state that the child was asked and did not answer")
                .isEmpty();
        assertThat(difference(difference(filled, placeholders), DOCUMENT_PROPERTIES))
                .as("ReportService fills these and the template has nowhere to put them, so they "
                        + "are silently missing from the export. Nothing reports this - not a log, "
                        + "not a warning, not the document itself")
                .isEmpty();
    }

    /**
     * The allow-list above is only worth having if it cannot lie. A key excused from needing a
     * placeholder must be read somewhere that is not the body, and this is where that is checked -
     * so adding a name to that set is a claim the build verifies rather than a comment it trusts.
     */
    @Test
    void everyValueExcusedFromNeedingAPlaceholderIsReallyADocumentProperty() throws IOException {
        String generator = Files.readString(DOCX_GENERATOR, StandardCharsets.UTF_8);
        String properties = generator.substring(generator.indexOf("applyDocumentProperties"));

        Set<String> unread = DOCUMENT_PROPERTIES.stream()
                .filter(key -> !properties.contains("\"" + key + "\""))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(unread)
                .as("these are excused from needing a ${token} on the grounds that they are read as "
                        + "document properties, and applyDocumentProperties does not mention them. "
                        + "Either the excuse is stale and the value is orphaned, or the name is "
                        + "misspelt and is quietly exempting something else")
                .isEmpty();
    }

    /**
     * The half that will actually fire. The other test guards a pair that is already reconciled; this
     * one guards the join that a future change goes through - somebody adds a question to
     * {@link ReportQuestions} and the export, which is the artefact nobody opens, quietly omits it.
     */
    @Test
    void everyQuestionInTheModelReachesTheExport() throws IOException {
        Set<String> placeholders = templatePlaceholders();

        Set<String> missing = ReportQuestions.ALL.stream()
                .map(ReportQuestion::exportToken)
                .filter(token -> !placeholders.contains(token))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(missing)
                .as("a question is asked, answered and stored, and then does not appear in the "
                        + "generated .docx at all. The export is the copy that leaves the system - "
                        + "it goes to social workers and to the police - so a question missing here "
                        + "is a question that, as far as anyone outside is concerned, was never asked")
                .isEmpty();
    }

    /**
     * The join between a question and its export token is normally the id, and exactly one question
     * differs. Pinning <em>which</em> matters more than pinning that some do: a divergence between a
     * question's identity and the name it goes out under is where a lossy transform hides, and the
     * one that exists is lossy. {@code heldAt} fills {@code ${interviewDate}}, which
     * {@code ReportService} formats from {@code getInterviewDate()} - the date without the time - in
     * a document that also states a 72-hour outcome derived from that time.
     *
     * <p>So this test is not a tidiness check. It is the thing that stops a second one being added
     * without anyone saying so, in the place where saying so is the entire safeguard.
     */
    @Test
    void exactlyOneQuestionGoesOutUnderADifferentNameThanItsOwn() {
        Set<String> renamed = ReportQuestions.ALL.stream()
                .filter(q -> !q.id().equals(q.exportToken()))
                .map(q -> q.id() + " -> " + q.exportToken())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(renamed)
                .as("a question whose export token differs from its id is a rename, and a rename is "
                        + "where a transform gets applied without a name. The known one is heldAt, "
                        + "which loses its time on the way out and is recorded on the model with "
                        + "the reason. A new entry here needs the same treatment before it lands")
                .containsExactly("heldAt -> interviewDate");
    }

    /**
     * Placeholders across every part of the document that can carry text.
     *
     * <p>XML tags are stripped before matching. Word routinely splits one visible run of text across
     * several {@code <w:r>} elements - {@code DocxReportGenerator} exists partly to cope with exactly
     * that - so {@code ${childName}} can appear in the XML as {@code ${child}} plus {@code Name}}.
     * Matching the raw XML would miss it, and missing a placeholder here fails in the dangerous
     * direction: the forward check would go quiet on a token that really is unfilled. Nothing in the
     * template is split today; that is not a property the next Word save preserves.
     */
    private static Set<String> templatePlaceholders() throws IOException {
        Set<String> found = new TreeSet<>();
        try (ZipFile docx = new ZipFile(TEMPLATE.toFile())) {
            var entries = docx.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("word/") || !name.endsWith(".xml")) {
                    continue;
                }
                String text;
                try (var in = docx.getInputStream(entry)) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                Matcher m = PLACEHOLDER.matcher(text.replaceAll("<[^>]*>", ""));
                while (m.find()) {
                    found.add(m.group(1));
                }
            }
        }
        return found;
    }

    /**
     * The keys ReportService actually puts into the substitution map.
     *
     * <p>Comments are stripped first. A commented-out {@code values.put("foo", ...)} - or a javadoc
     * line quoting one, which this codebase writes often - would otherwise be counted as a live
     * mapping, and the guard would report the template as complete on the strength of the
     * documentation describing it. Three guards here have shipped green off their own prose already.
     */
    private static Set<String> valuesMapKeys() throws IOException {
        String source = Files.readString(REPORT_SERVICE, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");

        Set<String> keys = new TreeSet<>();
        Matcher m = VALUES_PUT.matcher(source);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    private static Set<String> difference(Set<String> from, Set<String> minus) {
        return from.stream().filter(s -> !minus.contains(s))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}

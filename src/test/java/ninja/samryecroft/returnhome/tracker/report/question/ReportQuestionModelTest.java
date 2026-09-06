package ninja.samryecroft.returnhome.tracker.report.question;

import static org.assertj.core.api.Assertions.assertThat;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.dto.SubmitReportForm;
import org.junit.jupiter.api.Test;

/**
 * T185: the report's questions have <b>one</b> definition, and these are the reconciliations that
 * make a second one fail rather than pass quietly.
 *
 * <p><b>Why guards and not just the extraction.</b> Consolidating four hand-maintained copies into
 * one is only worth doing if a fifth cannot appear. Every copy this replaces was correct on the day
 * it was written; what nothing checked was that they stayed the same as each other, and two of them
 * had already drifted - {@code heldAt} was labelled "Date and time the interview was held" on the
 * capture and review screens and "Date of interview" on the record screen, on the one field the
 * statutory 72-hour measurement reads. Nothing was broken, nothing rendered wrong, and no test
 * failed. That is what this defect class looks like before it causes an incident, and it is the same
 * shape as T222: the wrong thing did not go red, it went silently green.
 *
 * <p>Each test below therefore pins a <em>relationship between two artefacts</em>, not the contents
 * of one. A test that asserted "there are 27 questions with these labels" would be a fifth copy.
 */
class ReportQuestionModelTest {

    /**
     * Form properties that are deliberately not questions, each with the reason it is excluded.
     * An allow-list rather than a filter, because the whole point is that adding a question-shaped
     * property without a model entry must be a decision somebody writes down.
     *
     * <ul>
     *   <li><b>within72Hours</b> - derived, not answered. It used to be a stored Yes/No the
     *       interviewer gave about their own compliance; it is now computed from {@code heldAt}
     *       against the return time ({@code InterviewReport.getWithin72Hours()}, {@code @Transient}).
     *       <b>The form property that backed it is now dead</b> - no control binds it, nothing reads
     *       it, and the entity has no setter for it - but removing it changes what the capture POST
     *       accepts, so it is named here and left for its own change rather than folded into a
     *       refactor that is otherwise behaviour-free.</li>
     *   <li><b>reviewComments</b> - the reviewer's note about the report, not an answer within it.
     *       Different author, different screen, different lifecycle.</li>
     *   <li><b>class</b> - {@code Object.getClass()}, an artefact of introspection.</li>
     * </ul>
     */
    private static final Set<String> NOT_QUESTIONS = Set.of("within72Hours", "reviewComments", "class");

    private static final Path SHARED_FRAGMENT =
            Path.of("src/main/resources/templates/fragments/report-fields.html");

    private static final Map<QuestionType, Class<?>> EXPECTED_JAVA_TYPE = Map.of(
            QuestionType.TEXT, String.class,
            QuestionType.LONG_TEXT, String.class,
            QuestionType.DATETIME, LocalDateTime.class,
            QuestionType.DATE, LocalDate.class,
            QuestionType.INTEGER, Integer.class,
            QuestionType.YES_NO, Boolean.class);

    @Test
    void everyQuestionHasADistinctId() {
        List<String> ids = ReportQuestions.ALL.stream().map(ReportQuestion::id).toList();

        assertThat(ids)
                .as("ids are the join between the model, the form, the export and any future "
                        + "consumer - a duplicate would silently make two questions one")
                .doesNotHaveDuplicates();
        assertThat(ReportQuestions.total())
                .as("a sanity floor, not a pin on the count: if this collapses to a handful the "
                        + "literal has been gutted and every reconciliation below passes vacuously")
                .isGreaterThan(20);
    }

    /**
     * <b>The one Kevin flagged as load-bearing.</b> {@code InterviewReport.getInterviewDate()} is a
     * lossy accessor named as if it were a field - it returns {@code heldAt.toLocalDate()} - and it
     * is what a string-keyed or reflective read reaches for, because its name is what a reader
     * expects "the interview date" to be called. Resolving {@code heldAt} to it would drop the time
     * of day from the single source of truth, which is worse than the original defect: it would put
     * it on every screen at once instead of one.
     *
     * <p>Written as a round-trip rather than a type assertion so that it fails on the actual harm -
     * the lost minutes - and not merely on the declared return type.
     */
    @Test
    void heldAtResolvesToTheDatetimeAndNotTheLossyDateAccessor() {
        LocalDateTime heldAt = LocalDateTime.of(2026, 3, 4, 15, 45);
        InterviewReport report = new InterviewReport();
        report.setHeldAt(heldAt);

        ReportQuestion question = ReportQuestions.byId("heldAt").orElseThrow();

        assertThat(question.type())
                .as("the 72-hour window is measured against this value's time of day")
                .isEqualTo(QuestionType.DATETIME);
        assertThat(question.valueOf(report))
                .as("this must read getHeldAt(). getInterviewDate() is heldAt.toLocalDate() and "
                        + "would return 2026-03-04 with the 15:45 gone - the T187 defect, reached "
                        + "from the single source of truth and therefore reproduced everywhere")
                .isEqualTo(heldAt);
        assertThat(question.hint())
                .as("a reader is told the time of day matters; the invariant is that the guidance "
                        + "mentions time, not its exact wording")
                .containsIgnoringCase("time");
    }

    /**
     * Every question reads the property of the same name, losslessly. This is the guard against a
     * reader being wired to a neighbouring field - a copy-paste error in a 27-entry literal that no
     * screen would reveal, because a wrong-but-populated value looks exactly like a right one.
     */
    @Test
    void everyQuestionReadsItsOwnPropertyOffTheReport() throws Exception {
        InterviewReport report = new InterviewReport();
        Map<String, Object> written = new java.util.HashMap<>();

        for (ReportQuestion question : ReportQuestions.ALL) {
            Object value = distinctValueFor(question);
            written.put(question.id(), value);
            PropertyDescriptor property = propertyOf(InterviewReport.class, question.id());
            assertThat(property)
                    .as("the model names a question '%s' that InterviewReport has no property for",
                            question.id())
                    .isNotNull();
            property.getWriteMethod().invoke(report, value);
        }

        for (ReportQuestion question : ReportQuestions.ALL) {
            assertThat(question.valueOf(report))
                    .as("question '%s' must read the report property of the same name, and must "
                            + "not narrow it on the way out", question.id())
                    .isEqualTo(written.get(question.id()));
        }
    }

    /**
     * <b>Both directions.</b> Forwards catches a question the capture form cannot collect; backwards
     * catches a question declared on the form and never added to the model - the second definition
     * reappearing where it is least visible, since a form property compiles and binds without any
     * screen showing it.
     */
    @Test
    void theModelAndTheCaptureFormDefineTheSameQuestions() throws IntrospectionException {
        Set<String> modelIds =
                ReportQuestions.ALL.stream().map(ReportQuestion::id).collect(Collectors.toSet());
        Set<String> formProperties = Arrays.stream(
                        Introspector.getBeanInfo(SubmitReportForm.class).getPropertyDescriptors())
                .filter(p -> p.getReadMethod() != null && p.getWriteMethod() != null)
                .map(PropertyDescriptor::getName)
                .filter(name -> !NOT_QUESTIONS.contains(name))
                .collect(Collectors.toSet());

        assertThat(modelIds)
                .as("a question the capture form cannot collect")
                .isSubsetOf(formProperties);
        assertThat(formProperties)
                .as("a bindable answer on SubmitReportForm with no entry in ReportQuestions - "
                        + "either it is a question and belongs in the model, or it is not one and "
                        + "belongs in NOT_QUESTIONS with the reason written down")
                .isSubsetOf(modelIds);
    }

    /** The declared type has to survive the trip, or the renderer picks the wrong control. */
    @Test
    void everyQuestionTypeMatchesThePropertyItIsBoundTo() throws IntrospectionException {
        List<String> mismatches = new ArrayList<>();
        for (ReportQuestion question : ReportQuestions.ALL) {
            PropertyDescriptor property = propertyOf(SubmitReportForm.class, question.id());
            Class<?> expected = EXPECTED_JAVA_TYPE.get(question.type());
            if (property == null || !expected.equals(property.getPropertyType())) {
                mismatches.add(question.id() + " is " + question.type() + " (expects " + expected
                        + ") but the form has "
                        + (property == null ? "no such property" : property.getPropertyType()));
            }
        }
        assertThat(mismatches)
                .as("the type drives the control on capture and the formatting on read; a "
                        + "disagreement here renders a datetime through a date formatter, which is "
                        + "how the time of day went missing in the first place")
                .isEmpty();
    }

    /**
     * The model must be a faithful extraction of the wording that is live today, not a retyping of
     * it. Apostrophes and dashes are the drift that survives review because it is invisible in a
     * diff - {@code home's} and {@code home’s} read identically and are different strings.
     *
     * <p>This is the pre-migration half of the template guard. Once the fragment renders <em>from</em>
     * the model there will be no literal labels left in it to compare against, and the assertion
     * inverts: no template may contain a question label at all. Landing it in this direction first
     * means the extraction itself is checked, which is the step where a transcription error is
     * possible and afterwards is not.
     */
    @Test
    void everyLabelIsTheWordingTheSharedFragmentAlreadyRenders() throws IOException {
        String fragment = withoutComments(Files.readString(SHARED_FRAGMENT, StandardCharsets.UTF_8));

        List<String> missing = ReportQuestions.ALL.stream()
                .map(ReportQuestion::label)
                .filter(label -> !fragment.contains(label))
                .toList();

        assertThat(missing)
                .as("these labels are not in fragments/report-fields.html character for character. "
                        + "The model is meant to be the wording that already ships, so a difference "
                        + "here is a transcription error, not a copy decision - and a copy decision "
                        + "on a safeguarding question is not one to make inside a refactor")
                .isEmpty();
    }

    /**
     * Comments are stripped before the fragment is searched. This file's own rationale quotes
     * question wording, and a scanner that reads its own documentation as data is a mistake this
     * codebase has made three times - the CSS guard, the audit-permanence guard, and the T184 log
     * guard all shipped passing on their own prose.
     */
    private static String withoutComments(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }

    private static PropertyDescriptor propertyOf(Class<?> type, String name)
            throws IntrospectionException {
        return Arrays.stream(Introspector.getBeanInfo(type).getPropertyDescriptors())
                .filter(p -> p.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    /** A value unique to the question, so a reader wired to the wrong property cannot coincide. */
    private static Object distinctValueFor(ReportQuestion question) {
        int seed = Math.abs(question.id().hashCode() % 1000);
        return switch (question.type()) {
            case TEXT, LONG_TEXT -> "answer-" + question.id();
            case DATETIME -> LocalDateTime.of(2026, 3, 4, 15, 45).plusMinutes(seed);
            case DATE -> LocalDate.of(2026, 3, 4).plusDays(seed);
            case INTEGER -> seed + 1;
            case YES_NO -> seed % 2 == 0;
        };
    }
}

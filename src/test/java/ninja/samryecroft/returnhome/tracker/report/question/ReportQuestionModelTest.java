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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final Path RECORD_SCREEN =
            Path.of("src/main/resources/templates/interview/detail.html");

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
     * <b>No screen holds its own copy of a question's wording.</b>
     *
     * <p>This assertion is the inverse of the one it replaces, and the inversion was predicted where
     * it stood: while the templates carried the literal labels, the model had to match them
     * character for character, because a transcription error was possible and that was the step to
     * check. Now the templates render <em>from</em> the model, so the thing to check is that none of
     * them has kept a copy - a stale literal beside a live one is drift that has already happened.
     *
     * <p>It looks only at question-label positions ({@code <dt>}, {@code <label>}) rather than at the
     * whole file, because a section HEADING may legitimately repeat a label: the Recommendations
     * card is headed "Recommendations" and its one question is also called "Recommendations". That
     * is one word doing two jobs, not two definitions of one.
     */
    @Test
    void noScreenKeepsItsOwnCopyOfAQuestionsWording() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path screen : List.of(SHARED_FRAGMENT, RECORD_SCREEN)) {
            String html = withoutComments(Files.readString(screen, StandardCharsets.UTF_8));
            Matcher element = Pattern.compile("<(dt|label)\\b[^>]*>(.*?)</\\1>", Pattern.DOTALL)
                    .matcher(html);
            while (element.find()) {
                String text = element.group(2).replaceAll("<[^>]*>", " ")
                        .replaceAll("\\s+", " ").trim();
                ReportQuestions.ALL.stream()
                        .filter(q -> q.label().equals(text))
                        .findFirst()
                        .ifPresent(q -> offences.add(screen.getFileName() + " writes out question '"
                                + q.id() + "' rather than rendering questions." + q.id()
                                + ".label - so the wording now exists in two places again"));
            }
        }
        assertThat(offences)
                .as("a label that lives in one place can only be right once; one that lives in two "
                        + "has to be KEPT right, and the field this project has already drifted on "
                        + "is the one the statutory 72-hour measurement reads")
                .isEmpty();
    }

    /**
     * And the screens ask <b>all</b> of the questions. The check above stops a second copy
     * appearing; this one stops a question quietly not being asked - the same defect from the other
     * side, and the one a loop would have prevented structurally.
     *
     * <p>Two questions are exempt on the record screen and named rather than filtered:
     * {@code interviewerComments} and {@code recommendations} render there as prose beneath a
     * section heading that already carries their wording, so there is no label element to render
     * from. That is different markup, not a missing question.
     */
    @Test
    void everyQuestionIsAskedOnEveryScreenThatAsksQuestions() throws IOException {
        String capture = Files.readString(SHARED_FRAGMENT, StandardCharsets.UTF_8);
        String record = Files.readString(RECORD_SCREEN, StandardCharsets.UTF_8);
        Set<String> headedBySectionTitle = Set.of("interviewerComments", "recommendations");

        List<String> missing = new ArrayList<>();
        for (ReportQuestion question : ReportQuestions.ALL) {
            String reference = "questions." + question.id() + ".label";
            if (!capture.contains(reference)) {
                missing.add("capture fragment does not ask '" + question.id() + "'");
            }
            if (!headedBySectionTitle.contains(question.id()) && !record.contains(reference)) {
                missing.add("record screen does not show '" + question.id() + "'");
            }
        }

        assertThat(missing)
                .as("a question in the model that no screen renders is asked of nobody, and a "
                        + "question on a screen that the model does not know about cannot be "
                        + "counted, exported or kept in step with the others")
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

package ninja.samryecroft.returnhome.tracker.report.question;

import static ninja.samryecroft.returnhome.tracker.report.question.QuestionType.DATE;
import static ninja.samryecroft.returnhome.tracker.report.question.QuestionType.DATETIME;
import static ninja.samryecroft.returnhome.tracker.report.question.QuestionType.INTEGER;
import static ninja.samryecroft.returnhome.tracker.report.question.QuestionType.LONG_TEXT;
import static ninja.samryecroft.returnhome.tracker.report.question.QuestionType.TEXT;
import static ninja.samryecroft.returnhome.tracker.report.question.QuestionType.YES_NO;
import static ninja.samryecroft.returnhome.tracker.report.question.ReportQuestion.NOT_ANSWERED;
import static ninja.samryecroft.returnhome.tracker.report.question.ReportSection.DECLARATION;
import static ninja.samryecroft.returnhome.tracker.report.question.ReportSection.DETAILS;
import static ninja.samryecroft.returnhome.tracker.report.question.ReportSection.FUTURE_INCIDENTS;
import static ninja.samryecroft.returnhome.tracker.report.question.ReportSection.INTERVIEWER_COMMENTS;
import static ninja.samryecroft.returnhome.tracker.report.question.ReportSection.RECOMMENDATIONS;
import static ninja.samryecroft.returnhome.tracker.report.question.ReportSection.RETURN_HOME_INTERVIEW;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;

/**
 * The return home interview's questions: <b>one ordered definition, and the only one.</b>
 *
 * <p><b>What this replaces.</b> The same 27 questions were written out by hand in four places - the
 * shared capture/review fragment, the record screen's own inline markup, the .docx template's
 * placeholders, and (for three of the six sections) a set of inline null-counting expressions that
 * produced the review screen's "N not answered" badges. Nothing checked any of them against any
 * other, and two had already drifted. A label that lives in one place can only be right once.
 *
 * <p><b>Order is declaration order, and there is no ordinal field.</b> Kevin measured all three
 * candidate downstream consumers and none is positional: the docx generator substitutes by key and
 * keeps its order in the template, the audit CSV writer carries no report questions at all, and the
 * {@code @Encrypted} listener keys its map by field name. An ordinal column would rot, and would make
 * every insertion a renumbering churn that reviewers stop reading. A {@code List.of(...)} literal is
 * already deterministic and diff-visible; the control on order is a test that pins the <em>rendered</em>
 * result, not a number in a row.
 *
 * <p><b>What is deliberately not here.</b> {@code within72Hours} is not a question. It was one -
 * an interviewer graded their own compliance, and an unanswered Yes/No cost an organisation exactly
 * what a real breach did - and it is now derived from {@code heldAt} against the return time
 * ({@code InterviewReport.getWithin72Hours()}, {@code @Transient}). It still appears in the export and
 * on the record screen, correctly, as a <em>fact about</em> the report rather than an answer within
 * it. Neither are the visitor, the submission timestamp, or the signature lines. The line matters
 * because it is the denominator of any "questions answered" figure: counting a derived fact as a
 * question would make the figure unreachable, since nobody can answer it.
 */
public final class ReportQuestions {

    private ReportQuestions() {
    }

    /**
     * Every question, in the order asked. Grouped by section for readability only - the sections
     * are carried on each entry, so a regrouping of this literal cannot silently move a question
     * between them.
     */
    public static final List<ReportQuestion> ALL = List.of(
            // --- 1. Details -------------------------------------------------------------------
            // heldAt fills ${interviewDate} in the .docx, not ${heldAt}, and the mismatch is not
            // cosmetic. That token is filled from getInterviewDate() - heldAt.toLocalDate() - so
            // THE EXPORTED RECORD SHOWS THE DAY AND NOT THE TIME, under a row headed "Date of
            // Interview", in a document whose head block states a 72-hour outcome computed from
            // precisely the time it has just withheld. A reader cannot check the conclusion against
            // the evidence, because the evidence is the half that was dropped.
            //
            // This is the same drift the record screen had ("Date of interview" against the capture
            // screen's "Date and time the interview was held"), which makes it three surfaces
            // disagreeing rather than two - and the export is the copy that leaves the building, to
            // social workers and to the police. Carried here rather than fixed here: correcting it
            // means editing a binary template and rewording a statutory document, which is Creed's
            // and Oscar's call and its own card. Recorded as data so the guard below stays armed and
            // the divergence cannot quietly acquire company.
            q("heldAt", DETAILS, "Date and time the interview was held",
                    "The 72-hour window is measured from the child's return to this time, so the "
                            + "time of day matters. Needed before this report can be submitted for "
                            + "review — you can save a draft without it.",
                    DATETIME, true, "interviewDate", InterviewReport::getHeldAt),
            q("interviewLocation", DETAILS, "Location of this interview", null,
                    TEXT, true, InterviewReport::getInterviewLocation),
            q("ifNotWhyLate", DETAILS, "If not, why?", null,
                    LONG_TEXT, false, InterviewReport::getIfNotWhyLate),
            q("consultationWithHomeStaff", DETAILS,
                    "Consultation with home's staff to establish any new information", null,
                    LONG_TEXT, false, InterviewReport::getConsultationWithHomeStaff),
            q("previouslyMissing", DETAILS, "Has this young person previously been missing?", null,
                    YES_NO, false, InterviewReport::getPreviouslyMissing),
            q("missingOccasionsLast30Days", DETAILS,
                    "How many occasions of missing has there been in the last 30 days?", null,
                    INTEGER, false, InterviewReport::getMissingOccasionsLast30Days),
            q("confidentialityExplained", DETAILS,
                    "Confidentiality explained to the young person regarding safeguarding duty of care?",
                    null, YES_NO, false, InterviewReport::getConfidentialityExplained),

            // --- 2. Return Home Interview -----------------------------------------------------
            q("interviewAccepted", RETURN_HOME_INTERVIEW, "Interview accepted?", null,
                    YES_NO, false, InterviewReport::getInterviewAccepted),
            q("interviewDeclinedReason", RETURN_HOME_INTERVIEW, "If not, why?", null,
                    LONG_TEXT, false, InterviewReport::getInterviewDeclinedReason),
            q("whereWereYouWhileMissing", RETURN_HOME_INTERVIEW, "Where were you while missing?", null,
                    LONG_TEXT, false, InterviewReport::getWhereWereYouWhileMissing),
            q("whoWereYouWithWhileMissing", RETURN_HOME_INTERVIEW, "Who were you with while missing?",
                    null, LONG_TEXT, false, InterviewReport::getWhoWereYouWithWhileMissing),
            q("whatMadeYouGoMissing", RETURN_HOME_INTERVIEW, "What made you go missing?", null,
                    LONG_TEXT, false, InterviewReport::getWhatMadeYouGoMissing),
            q("whatCanBeDoneToAddressReasons", RETURN_HOME_INTERVIEW,
                    "What can be done to address these reasons?", null,
                    LONG_TEXT, false, InterviewReport::getWhatCanBeDoneToAddressReasons),
            q("consideredSelfMissing", RETURN_HOME_INTERVIEW,
                    "Did you consider yourself to be missing?", null,
                    YES_NO, false, InterviewReport::getConsideredSelfMissing),
            q("whatDidYouDoWhileMissing", RETURN_HOME_INTERVIEW,
                    "What did you do while you were missing?", null,
                    LONG_TEXT, false, InterviewReport::getWhatDidYouDoWhileMissing),
            q("whatHappenedWhenReturned", RETURN_HOME_INTERVIEW,
                    "What happened when you returned home?", null,
                    LONG_TEXT, false, InterviewReport::getWhatHappenedWhenReturned),
            q("preventFutureMissingSuggestions", RETURN_HOME_INTERVIEW,
                    "Is there anything that can be done to stop you going missing again?", null,
                    LONG_TEXT, false, InterviewReport::getPreventFutureMissingSuggestions),
            q("additionalCommentsFromYoungPerson", RETURN_HOME_INTERVIEW,
                    "Any additional comments from the young person?", null,
                    LONG_TEXT, false, InterviewReport::getAdditionalCommentsFromYoungPerson),
            q("additionalInfoFromParentCarer", RETURN_HOME_INTERVIEW,
                    "Any additional information provided by the parent/carer?", null,
                    LONG_TEXT, false, InterviewReport::getAdditionalInfoFromParentCarer),

            // --- 3. Future Incidents ----------------------------------------------------------
            q("risksIdentifiedDuringEpisode", FUTURE_INCIDENTS,
                    "Any identified risks during this missing episode?", null,
                    LONG_TEXT, false, InterviewReport::getRisksIdentifiedDuringEpisode),
            q("risksIncreaseFutureEpisodes", FUTURE_INCIDENTS,
                    "Is there anything that would increase risks during future missing episodes?", null,
                    LONG_TEXT, false, InterviewReport::getRisksIncreaseFutureEpisodes),
            q("safeguardingConcernsToExplore", FUTURE_INCIDENTS,
                    "Any safeguarding concerns that need to be further explored?", null,
                    LONG_TEXT, false, InterviewReport::getSafeguardingConcernsToExplore),
            q("infoToHelpLocateFuture", FUTURE_INCIDENTS,
                    "Any information that might help locating the young person during future missing episodes?",
                    null, LONG_TEXT, false, InterviewReport::getInfoToHelpLocateFuture),

            // --- 4. Interviewer's Comments ----------------------------------------------------
            q("interviewerComments", INTERVIEWER_COMMENTS, "Comments", null,
                    LONG_TEXT, false, InterviewReport::getInterviewerComments),

            // --- 5. Recommendations -----------------------------------------------------------
            q("recommendations", RECOMMENDATIONS, "Recommendations",
                    "Following analysis of the information held within this report, and "
                            + "observations/discussions during the visit.",
                    LONG_TEXT, false, InterviewReport::getRecommendations),

            // --- 6. Declaration ---------------------------------------------------------------
            q("conductedByStatement", DECLARATION, "Statement", null,
                    LONG_TEXT, false, InterviewReport::getConductedByStatement),
            new ReportQuestion("dateReportShared", DECLARATION,
                    "Date report shared with relevant professionals (leave blank if not yet shared)",
                    null, DATE, false, "Not shared yet", "dateReportShared",
                    InterviewReport::getDateReportShared));

    private static ReportQuestion q(String id, ReportSection section, String label, String hint,
            QuestionType type, boolean required, Function<InterviewReport, Object> reader) {
        return q(id, section, label, hint, type, required, id, reader);
    }

    private static ReportQuestion q(String id, ReportSection section, String label, String hint,
            QuestionType type, boolean required, String exportToken,
            Function<InterviewReport, Object> reader) {
        return new ReportQuestion(
                id, section, label, hint, type, required, NOT_ANSWERED, exportToken, reader);
    }

    /** Every question, in order, grouped by the section that asks it. */
    public static Map<ReportSection, List<ReportQuestion>> bySection() {
        Map<ReportSection, List<ReportQuestion>> grouped = new LinkedHashMap<>();
        for (ReportSection section : ReportSection.values()) {
            grouped.put(section, ALL.stream().filter(q -> q.section() == section).toList());
        }
        return grouped;
    }

    public static List<ReportQuestion> of(ReportSection section) {
        return ALL.stream().filter(q -> q.section() == section).toList();
    }

    public static Optional<ReportQuestion> byId(String id) {
        return ALL.stream().filter(q -> q.id().equals(id)).findFirst();
    }

    /** How many questions there are. A fold over the one definition, never a maintained number. */
    public static int total() {
        return ALL.size();
    }

    /** How many of them this report answers. */
    public static int answered(InterviewReport report) {
        return (int) ALL.stream().filter(q -> q.isAnsweredOn(report)).count();
    }

    /** How many the given section leaves unanswered - the review screen's "N not answered" badge. */
    public static int unansweredIn(ReportSection section, InterviewReport report) {
        return (int) of(section).stream().filter(q -> !q.isAnsweredOn(report)).count();
    }
}

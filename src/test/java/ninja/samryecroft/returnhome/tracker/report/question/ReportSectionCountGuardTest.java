package ninja.samryecroft.returnhome.tracker.report.question;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import org.junit.jupiter.api.Test;

/**
 * T185 step 2: the "N not answered" badges are a fold over the one question set, and <b>every
 * section has one</b>.
 *
 * <p><b>The asymmetry is the part worth guarding.</b> Sections 1, 2 and 3 counted, using
 * null-checks written out by hand; sections 4, 5 and 6 had no badge markup at all. So an absent
 * badge meant "complete" on three cards and "never counted" on the other three, and nothing on the
 * page distinguished them - <b>an absence reads as "nothing to answer" whichever it is</b>, and the
 * uncounted case is the one that quietly understates what a reviewer still has to check.
 *
 * <p>That is why this guard asserts the badge is present on all six rather than merely that the
 * count is right. A correct count on three cards is what the screen already had.
 *
 * <p><b>And the hand-written version could not have been right.</b> One question is conditional -
 * {@code ifNotWhyLate} is asked only when the 72-hour window was measured and missed - so a blank
 * means two opposite things, and counting every blank reported a fully completed, on-time interview
 * as having a gap in it. An expression in a template can only get that right by repeating the
 * condition, which is a third copy of a rule that already exists twice.
 */
class ReportSectionCountGuardTest {

    private static final List<Path> READ_ONLY_SCREENS = List.of(
            Path.of("src/main/resources/templates/interview/detail.html"),
            Path.of("src/main/resources/templates/fragments/report-fields.html"));

    /** A card whose id is one of the report's own sections, and whatever count it declares. */
    private static final Pattern REPORT_CARD = Pattern.compile(
            "<div class=\"card\" id=\"([\\w-]+)\"[^>]*?(?:\\n[^>]*?)?>", Pattern.DOTALL);

    private static final LocalDateTime RETURNED = LocalDateTime.of(2026, 9, 2, 14, 20);

    @Test
    void everySectionOfTheReportCarriesACountSoAnAbsentBadgeMeansOneThing() throws IOException {
        List<String> offences = new ArrayList<>();

        for (Path screen : READ_ONLY_SCREENS) {
            String html = withoutComments(Files.readString(screen, StandardCharsets.UTF_8));
            for (ReportSection section : ReportSection.values()) {
                String card = cardFor(html, section.getAnchorId());
                if (card == null) {
                    offences.add(screen.getFileName() + " has no card for section '"
                            + section.getAnchorId() + "'");
                    continue;
                }
                if (!card.contains("unansweredBySection['" + section.getAnchorId() + "']")) {
                    offences.add(screen.getFileName() + " section '" + section.getAnchorId()
                            + "' does not take its count from the model - so it is either counting "
                            + "by hand or not counting at all, and an absent badge there means "
                            + "something different from an absent badge elsewhere on the page");
                }
            }
        }

        assertThat(offences)
                .as("an absent badge must have exactly one meaning. While three sections counted "
                        + "and three did not, 'no badge' meant 'complete' on half the cards and "
                        + "'never counted' on the other half, and a reader had no way to tell - "
                        + "which understates what is still outstanding on a safeguarding record")
                .isEmpty();
    }

    /**
     * The count itself, on the case that was wrong before T233 and would be wrong again the moment
     * anyone reimplements it as "how many fields are blank".
     */
    @Test
    void aQuestionThatDoesNotApplyIsNotCountedAsUnanswered() {
        InterviewReport onTime = report(RETURNED.plusHours(10), null);
        InterviewReport late = report(RETURNED.plusHours(80), null);

        assertThat(ReportQuestions.unansweredIn(ReportSection.DETAILS, onTime))
                .as("on time, so no explanation was ever owed. A fully completed interview must not "
                        + "report a gap - this is the compliance-shaped number that used to appear "
                        + "on the screen a reviewer approves from")
                .isEqualTo(ReportQuestions.unansweredIn(ReportSection.DETAILS, late) - 1);

        assertThat(ReportQuestions.byId("ifNotWhyLate").orElseThrow().isAGapOn(onTime))
                .as("the condition belongs to the question, not to whichever renderer is asking")
                .isFalse();
        assertThat(ReportQuestions.byId("ifNotWhyLate").orElseThrow().isAGapOn(late)).isTrue();
    }

    /**
     * The second question whose blank is not a gap, and the one this change was nearly shipped
     * without.
     *
     * <p>{@code dateReportShared} is always asked, and its blank is an <em>answer</em>: "not yet
     * shared". The capture screen instructs the user to leave it empty in that case. Counting it as
     * unanswered is <b>the system reporting a person as having declined to answer a question it told
     * them not to answer</b> - the same mistake as ifNotWhyLate arriving from a different direction,
     * which is why both are one concept on the model rather than two flags.
     *
     * <p>It went unnoticed for as long as it did because the Declaration section had no badge at
     * all. Giving every section a count is what made it visible - <b>a fix that reveals a defect it
     * did not cause still has to carry it.</b>
     */
    @Test
    void aBlankThatTheFormAsksForIsNotAGap() {
        InterviewReport notYetShared = report(RETURNED.plusHours(10), null);
        // The section's OTHER question is answered, so a non-zero count below could only be
        // dateReportShared. Leaving both blank would have made this assertion pass or fail for
        // reasons it does not name.
        notYetShared.setConductedByStatement("Conducted by A. Visitor");

        assertThat(ReportQuestions.byId("dateReportShared").orElseThrow().isAGapOn(notYetShared))
                .as("blank here means 'not yet shared', which is a fact about the report rather "
                        + "than a hole in it")
                .isFalse();
        assertThat(ReportQuestions.unansweredIn(ReportSection.DECLARATION, notYetShared))
                .as("an approved report that simply has not been shared yet must not show a "
                        + "compliance-shaped number on the screen a reviewer approves from")
                .isZero();
    }

    /**
     * The third question of this family, and <b>the worst of the three</b>.
     *
     * <p>{@code interviewDeclinedReason} is anchored to "Interview accepted?" exactly as the 72-hour
     * reason is anchored to the window being missed. Counting it meant that an interview which WAS
     * accepted - the normal, successful outcome - reported "1 not answered" in section 2, on the
     * screen a reviewer approves from. <b>The good outcome was the one that got flagged.</b> The
     * other two at least fired on unusual states.
     *
     * <p>It surfaced for the same reason as the Declaration case: section 2 had no badge before this
     * change either, so giving every section a count is what would have revealed it. <b>A fix that
     * reveals a defect it did not cause still has to carry it</b> - and this is the second time that
     * rule has applied to this one branch.
     *
     * <p>It was a one-line fix because {@code blankIsAGap} is a predicate rather than a flag. The
     * modelling decision is what made the third instance cheap.
     */
    @Test
    void anAcceptedInterviewDoesNotReportTheDeclinedReasonAsAGap() {
        ReportQuestion declinedReason = ReportQuestions.byId("interviewDeclinedReason").orElseThrow();

        InterviewReport accepted = report(RETURNED.plusHours(10), null);
        accepted.setInterviewAccepted(true);
        assertThat(declinedReason.isAGapOn(accepted))
                .as("the interview happened, so nobody was ever asked why it did not")
                .isFalse();

        InterviewReport declined = report(RETURNED.plusHours(10), null);
        declined.setInterviewAccepted(false);
        assertThat(declinedReason.isAGapOn(declined))
                .as("declined and no reason written down - a real gap, and the one case this "
                        + "question exists for")
                .isTrue();

        InterviewReport notRecorded = report(RETURNED.plusHours(10), null);
        assertThat(declinedReason.isAGapOn(notRecorded))
                .as("'Interview accepted?' is itself unanswered, which is a gap in its own right. It "
                        + "must not also manufacture a second one here - the same treatment the "
                        + "72-hour reading gives a window it cannot measure")
                .isFalse();
    }

    @Test
    void everySectionAppearsInTheCountsIncludingTheOnesThatComeOutZero() {
        Map<String, Integer> counts = ReportQuestions.unansweredBySection(report(null, null));

        assertThat(counts.keySet())
                .as("a section missing from this map renders no badge, which is the asymmetry this "
                        + "whole change exists to remove")
                .containsExactlyElementsOf(
                        java.util.Arrays.stream(ReportSection.values())
                                .map(ReportSection::getAnchorId).toList());
    }

    /** The card element and its attributes, which may wrap across lines. */
    private static String cardFor(String html, String anchorId) {
        Matcher m = Pattern.compile("<div class=\"card\" id=\"" + Pattern.quote(anchorId)
                + "\"[^>]*>", Pattern.DOTALL).matcher(html);
        return m.find() ? m.group() : null;
    }

    /**
     * Comments stripped first. The rationale added beside these badges names the very expression it
     * replaced, so a scan reading its own explanation would pass on the description of the defect.
     */
    private static String withoutComments(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }

    private InterviewReport report(LocalDateTime heldAt, String reason) {
        InterviewRequest request = new InterviewRequest();
        request.setReturnedAt(RETURNED);
        InterviewReport report = new InterviewReport();
        report.setInterviewRequest(request);
        report.setHeldAt(heldAt);
        report.setIfNotWhyLate(reason);
        return report;
    }
}

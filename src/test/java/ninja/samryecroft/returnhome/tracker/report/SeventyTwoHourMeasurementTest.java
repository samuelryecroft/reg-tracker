package ninja.samryecroft.returnhome.tracker.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import org.junit.jupiter.api.Test;

/**
 * T97: the 72-hour outcome is measured from two recorded times, not declared by the person whose
 * compliance it describes.
 *
 * <p>The value it replaced was a stored Yes/No/Unknown. Its third state was the defect: null failed
 * the {@code Boolean.TRUE.equals} test the dashboard used, so an unanswered question was counted as
 * a breach while still sitting in the denominator - an incomplete record was indistinguishable from
 * a late interview. Null now means "not measurable" and is excluded from both sides instead.
 */
class SeventyTwoHourMeasurementTest {

    private static final LocalDateTime RETURNED = LocalDateTime.of(2026, 7, 16, 20, 30);

    private InterviewReport reportHeldAt(LocalDateTime heldAt) {
        InterviewRequest request = new InterviewRequest();
        request.setReturnedAt(RETURNED);
        InterviewReport report = new InterviewReport();
        report.setInterviewRequest(request);
        report.setHeldAt(heldAt);
        return report;
    }

    @Test
    void anInterviewInsideTheWindowMetIt() {
        assertThat(reportHeldAt(RETURNED.plusHours(10)).getWithin72Hours()).isTrue();
    }

    @Test
    void theBoundaryItselfCounts() {
        // Exactly 72 hours is within the statutory window, not outside it - and the whole reason
        // the form now collects a time as well as a date is that a date alone cannot tell these
        // two cases apart.
        assertThat(reportHeldAt(RETURNED.plusHours(72)).getWithin72Hours()).isTrue();
        assertThat(reportHeldAt(RETURNED.plusHours(72).plusMinutes(1)).getWithin72Hours()).isFalse();
    }

    @Test
    void anInterviewOutsideTheWindowDidNot() {
        assertThat(reportHeldAt(RETURNED.plusHours(100)).getWithin72Hours()).isFalse();
    }

    @Test
    void anUnrecordedInterviewTimeIsNotMeasurableRatherThanABreach() {
        // The distinction the old stored boolean could not make, and the reason this is null rather
        // than false: false is a finding about the organisation, null is an absence of evidence.
        assertThat(reportHeldAt(null).getWithin72Hours()).isNull();
    }

    /*
     * T228: theCalendarDateIsDerivedSoItCannotDisagreeWithTheMeasurement() used to be here, and it
     * went with the accessor it tested rather than being quietly dropped.
     *
     * It pinned that getInterviewDate() equalled heldAt.toLocalDate(). That was true, and it was
     * also the promise that made truncation the default for anyone who did not opt in - so the test
     * was, in effect, guarding the mechanism of the defect. Deleting the accessor is what T228 is;
     * a test asserting it still behaved correctly would have had to be deleted or made to lie.
     *
     * What replaced it is not another test of a getter. The one place that legitimately wants a date
     * is the document's core title, and DocxReportGeneratorTest now pins that directly:
     * aMissingInterviewTimeShortensTheTitleRatherThanNamingTheDocumentAfterTheGap. Truncation
     * happens once, out loud, at that call site.
     */
}

package ninja.samryecroft.returnhome.tracker.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.interview.QueueFilter;
import ninja.samryecroft.returnhome.tracker.organisation.OrgStatus;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.report.ReportStatus;
import ninja.samryecroft.returnhome.tracker.user.AppearancePreference;
import ninja.samryecroft.returnhome.tracker.user.Role;
import org.junit.jupiter.api.Test;

/**
 * T332: the values that are allowed to be renamed are unbounded and need comprehension. <b>The
 * values that must NEVER be renamed are bounded and stable, so those are the ones worth pinning.</b>
 *
 * <p><b>The problem this inverts.</b> A copy rename has to answer "which string literals are
 * user-facing", and inside Java that question has no mechanical form. These two sit in the same
 * package and look identical to any positional rule:
 *
 * <pre>
 *   .target("Child", child.getId())                            MUST NOT change - stored discriminator
 *   "The 72-hour window is measured from the child's return"   MUST change     - user-facing copy
 * </pre>
 *
 * What separates them is <em>where the value ends up</em> - a screen or a database column - which is
 * not visible in the text. So the enumerated list of copy sites stays human. <b>This test guards the
 * other half: a sweeper that renames one of these goes red whatever rule it was following, including
 * a rule nobody has written yet.</b>
 *
 * <p><b>Why the inversion is worth building rather than merely tidier.</b> Not because the list is
 * shorter - because it <em>holds still</em>. Copy changes constantly and by design; discriminators
 * and persisted keys change almost never. A hand-maintained list of "never change these" decays far
 * more slowly than a hand-maintained list of "these are copy".
 *
 * <p><b>Pinned through the accessor, never through source text.</b>
 * {@code QueueFilter.NO_CLOCK} is {@code ("noClock", "Return time not recorded", …)} - a URL key and
 * a label, adjacent, one of each. A pin written against that line's text would go red on a
 * legitimate copy edit, and <b>a guard that fires on correct work teaches everyone to ignore it</b>.
 * Reading {@code key()} protects exactly one of the two. The single exception is
 * {@code targetType}, which has no accessor; its own note explains why reading source is safe there.
 *
 * <p><b>Adding a constant fails this test on purpose.</b> A new persisted value is a schema-visible
 * decision, and the failure is the prompt to make it deliberately. That is a different axis from
 * {@link ninja.samryecroft.returnhome.tracker.audit.AuditEventVocabularyTest}, which covers new
 * constants automatically because it guards what they <em>display</em>; this guards what they
 * <em>are</em>.
 *
 * <p><b>Armed before it was trusted</b>, five ways: renaming a persisted enum constant, renaming a
 * URL key, changing a stored discriminator and adding an unpinned constant each turn it red - and
 * <b>changing the label beside a URL key leaves it green</b>, which is the case that separates a
 * useful guard from an always-amber one.
 */
class PersistedConstantsGuardTest {

    @Test
    void auditEventTypeNames() {
        assertThat(names(AuditEventType.values())).containsExactlyInAnyOrder(
                "LOGIN_SUCCESS", "LOGIN_FAILURE", "MFA_CHALLENGE_ISSUED", "MFA_SUCCESS",
                "MFA_FAILURE", "MFA_LOCKED", "BREAK_GLASS_LOGIN", "BREAK_GLASS_ENABLED",
                "USER_CREATED", "USER_UPDATED", "USER_PASSWORD_RESET", "USER_EMAIL_CHANGED",
                "INTERVIEW_REQUEST_CREATED", "INTERVIEW_REQUEST_ALLOCATED",
                "INTERVIEW_REQUEST_SCHEDULED", "INTERVIEW_REQUEST_RETURN_TIME_RECORDED",
                "REPORT_DRAFT_SAVED", "REPORT_SUBMITTED", "REPORT_APPROVED", "REPORT_REJECTED",
                "DOCX_GENERATED", "DOCX_DOWNLOADED", "DOCUMENT_KEY_WRAPPED",
                "DOCUMENT_KEY_UNWRAPPED", "DOCUMENT_CRYPTO_FAILED", "CASE_FILE_EXPORTED",
                "AUDIT_QUERY_EXPORTED", "EXPORT_FAILED", "AUDIT_VIEW_OPENED", "NAMES_REVEALED",
                "ORGANISATION_ACTIVATED", "CHILD_UPDATED", "CHILD_ARCHIVED", "CHILD_RESTORED",
                "ORGANISATION_ARCHIVED", "ORGANISATION_RESTORED", "ACCESS_DENIED");
    }

    @Test
    void interviewStatusNames() {
        assertThat(names(InterviewStatus.values())).containsExactlyInAnyOrder(
                "REQUESTED", "ALLOCATED", "SCHEDULED", "REPORT_SUBMITTED", "REPORT_REJECTED",
                "REPORT_APPROVED", "CANCELLED");
    }

    @Test
    void reportStatusNames() {
        assertThat(names(ReportStatus.values()))
                .containsExactlyInAnyOrder("DRAFT", "SUBMITTED", "REJECTED", "APPROVED");
    }

    @Test
    void roleNames() {
        assertThat(names(Role.values())).containsExactlyInAnyOrder(
                "HOME_STAFF", "ORG_ADMIN", "COORDINATOR", "VISITOR", "REVIEWER", "VIEWER", "ADMIN");
    }

    @Test
    void orgTypeNames() {
        assertThat(names(OrgType.values())).containsExactlyInAnyOrder("SUPPLIER", "CARE_PROVIDER");
    }

    /**
     * T332's own hardest case, and the reason the pin is written through {@code key()}.
     *
     * <p>{@code NO_CLOCK("noClock", "Return time not recorded", …)} carries two adjacent string
     * literals: a URL key that must never change, and user-facing copy that is in scope for any
     * rename. <b>No positional rule separates them</b> - they differ by where the value ends up,
     * which is not lexically visible. A pin written against the text of that line would go red on a
     * legitimate copy change, and <b>a guard that fires on correct work teaches everyone to ignore
     * it</b>. Reading {@code key()} is precise about which of the two it protects, and survives
     * reformatting.
     *
     * <p>These are bookmarkable URLs, so a rename breaks links people have already sent.
     */
    @Test
    void queueFilterUrlKeys() {
        assertThat(Arrays.stream(QueueFilter.values()).map(QueueFilter::key).toList())
                .containsExactlyInAnyOrder("unallocated", "awaitingReport", "awaitingReview",
                        "closed", "overdue", "dueSoon", "noClock", "awaitingSchedule", "consent");
    }

    /**
     * The audit trail's {@code targetType} discriminators, and the one family here with no accessor
     * to read: they are inline literals in {@code AuditEventPublisher}.
     *
     * <p><b>So this pin reads source text, and that is a deliberate exception rather than an
     * oversight.</b> The objection to source-text pins is that they misfire on legitimate copy
     * changes. This one cannot: it reads only the FIRST argument of {@code .target(}, which is a
     * stored discriminator and is never copy. It asserts the SET, not any line, so reformatting and
     * reordering leave it alone. {@code AuditPermanenceClaimGuardTest} sets the precedent for
     * reading a file when the thing being pinned has no accessor.
     *
     * <p><b>Why the value cannot change:</b> {@code audit_events} is append-only. Renaming a
     * discriminator does not rename history, it SPLITS it - rows before keep the old value, rows
     * after get the new one, and every {@code findByTargetTypeAndTargetId} returns half a record.
     * The trail exists so a DPO or a court can answer "who looked at this young person's record",
     * and a split discriminator makes that answer wrong rather than untidy, silently, and only
     * under inspection.
     */
    @Test
    void auditTargetTypeDiscriminators() throws IOException {
        Path publisher = Path.of("src/main/java/ninja/samryecroft/returnhome/tracker/audit/"
                + "AuditEventPublisher.java");
        Matcher m = Pattern.compile("\\.target\\(\"([A-Za-z]+)\"").matcher(Files.readString(publisher));
        List<String> found = new java.util.ArrayList<>();
        while (m.find()) {
            if (!found.contains(m.group(1))) {
                found.add(m.group(1));
            }
        }
        assertThat(found)
                .as("if this is empty the pin stopped reading the file and is no longer measuring "
                        + "anything - an empty result here is a failure, not a pass")
                .isNotEmpty();
        assertThat(found).containsExactlyInAnyOrder("User", "Organisation", "Child",
                "InterviewRequest", "InterviewReport", "Page", "HttpRequest");
    }

    /**
     * Both of these were missing from the first version of this pin, and how they were missing is
     * the reason the list is now derived from the annotation rather than from the entities.
     *
     * <p>I enumerated the persisted enums by finding the entity classes that carry
     * {@code @Enumerated(EnumType.STRING)} and then listing, by reading, which enum types they hold.
     * Five of seven. {@code OrgStatus} and {@code AppearancePreference} were on fields I did not
     * look at. <b>An enumeration reports the absence of what its instrument could not see as an
     * absence in the world</b> - and the instrument here was my own reading.
     *
     * <p>The check that finds all seven is one line and asks the annotation directly:
     * {@code grep -rA3 '@Enumerated(EnumType.STRING)' src/main/java | grep 'private'}. If a new
     * persisted enum appears, that is what to run - this test cannot notice a whole enum it was
     * never told about, only a constant inside one it holds.
     */
    @Test
    void orgStatusNames() {
        assertThat(names(OrgStatus.values()))
                .containsExactlyInAnyOrder("PENDING", "ACTIVE", "ARCHIVED");
    }

    @Test
    void appearancePreferenceNames() {
        assertThat(names(AppearancePreference.values()))
                .containsExactlyInAnyOrder("LIGHT", "DARK", "AUTO");
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}

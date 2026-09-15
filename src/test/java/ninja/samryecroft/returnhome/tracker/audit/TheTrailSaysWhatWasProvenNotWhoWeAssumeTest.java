package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * T359: what the audit trail records about an actor it never identified.
 *
 * <h2>The two defects this exists to stop coming back</h2>
 *
 * <p><b>1. The snapshot was empty.</b> Seven publisher methods took a {@code User} and recorded
 * {@code user.getUsername()}. Since T344 usernames are no longer issued: the column is null for
 * everybody except the break-glass account. So {@code actor_identifier_at_time} - the column V26
 * renamed <em>specifically</em> so it would hold whatever identified the actor - was NULL on every
 * MFA and password-reset row ever written. The row still carried {@code actor_id}, so the trail
 * looked populated and nothing failed; the part that was missing is the part that only matters
 * later, when somebody asks who <em>that</em> was and the account has since changed hands or
 * addresses.
 *
 * <p><b>2. The actor asserted more than the flow could know.</b> {@code /forgot-password} is
 * unauthenticated. It proves control of a mailbox at two moments and never proves identity. A row
 * reading as "the account holder did this" is an overstatement, and in an append-only table an
 * overstatement made today cannot be corrected tomorrow. It matters most on a FAILED attempt, which
 * is precisely the row an investigator reads when they suspect somebody was trying: naming the
 * holder there puts their name against somebody else's attempt to break into their account.
 *
 * <p><b>What is recorded instead:</b> the account (because it is the only identity in play and the
 * row must attach to it), the ADDRESS control was demonstrated over, and the MECHANISM in the
 * metadata - so the reader is told how the act was authorised rather than left to infer who.
 */
@SpringBootTest
class TheTrailSaysWhatWasProvenNotWhoWeAssumeTest extends AbstractIntegrationTest {

    @Autowired private AuditEventPublisher publisher;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private UserRepository userRepository;

    private User ordinaryAccount() {
        User user = new User();
        String unique = "t359-" + System.nanoTime();
        user.setUsername(null);
        user.setEmail(unique + "@example.test");
        user.setFirstName("Trail");
        user.setLastName("Reader");
        user.setPassword("not-checked-here");
        user.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private AuditEvent latest(AuditEventType type) {
        return auditEvents.findByEventTypeOrderByOccurredAtDesc(type).get(0);
    }

    /**
     * THE ASSERTION FOR DEFECT 1, and it is deliberately about an account with NO username - which
     * since T344 is every account a person uses.
     */
    @Test
    void anOrdinaryAccountIsIdentifiableInTheTrailEvenThoughItHasNoUsername() {
        User user = ordinaryAccount();
        assertThat(user.getUsername())
                .as("PRECONDITION: people have no username since T344 - if this ever becomes "
                        + "non-null the test has stopped exercising the case that was broken")
                .isNull();

        publisher.passwordResetCompleted(user);

        assertThat(latest(AuditEventType.PASSWORD_RESET_COMPLETED).getActorIdentifierAtTime())
                .as("the point-in-time snapshot must survive this account later changing its "
                        + "address, so it has to have been written in the first place")
                .isEqualTo(user.getEmail());
    }

    /** Same defect, the other family of rows it silently emptied. */
    @Test
    void theSameIsTrueOfTheSecondFactorRows() {
        User user = ordinaryAccount();

        publisher.mfaSuccess(user);

        assertThat(latest(AuditEventType.MFA_SUCCESS).getActorIdentifierAtTime())
                .isEqualTo(user.getEmail());
    }

    /**
     * THE ASSERTION FOR DEFECT 2: the row states how the act was authorised, so a reader is not left
     * to assume it was the person.
     */
    @Test
    void aSelfServiceResetRecordsTheMechanismThatAuthorisedIt() {
        User user = ordinaryAccount();

        publisher.passwordResetCompleted(user);

        AuditEvent row = latest(AuditEventType.PASSWORD_RESET_COMPLETED);
        assertThat(row.getMetadata())
                .as("an IRO or a court reading this years from now must be able to tell a "
                        + "self-service reset from an administrator's one without reading our code")
                .contains("emailed-link");
        assertThat(row.getActorId())
                .as("and the row must still say WHICH ACCOUNT was affected - that is not the part "
                        + "being softened")
                .isEqualTo(user.getId());
    }

    /**
     * The failure row carries it too. This is the row that gets read when somebody suspects an
     * attempt was made, so it is the one where "the account holder did this" would do real harm.
     */
    @Test
    void aFailedResetAttemptAlsoSaysHowItWasAuthorisedRatherThanNamingTheHolderAlone() {
        User user = ordinaryAccount();

        publisher.passwordResetFailed(user, "wrong-code");

        AuditEvent row = latest(AuditEventType.PASSWORD_RESET_FAILED);
        assertThat(row.getMetadata()).contains("emailed-link").contains("wrong-code");
        assertThat(row.getActorIdentifierAtTime()).isEqualTo(user.getEmail());
    }

    /**
     * The break-glass account is the one case where the identifier is still a NAME, because that
     * account deliberately has no address. Asserted so that "use the email everywhere" cannot be
     * applied to it by a later tidy-up - it would write null and lose the only account whose actions
     * most need attributing.
     */
    @Test
    void theBreakGlassAccountIsStillIdentifiedByItsName() {
        User emergency = userRepository.findByBreakGlassTrue().orElse(null);
        if (emergency == null) {
            return; // No seed password configured in this context; covered where one is.
        }

        publisher.mfaSuccess(emergency);

        assertThat(latest(AuditEventType.MFA_SUCCESS).getActorIdentifierAtTime())
                .as("it has no address, so its name is what identifies it - and its actions are the "
                        + "ones an investigator is most likely to be looking for")
                .isEqualTo(emergency.getUsername());
    }
}

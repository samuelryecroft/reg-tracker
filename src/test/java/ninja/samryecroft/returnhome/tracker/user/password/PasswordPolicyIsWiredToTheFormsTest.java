package ninja.samryecroft.returnhome.tracker.user.password;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.user.dto.CreateUserForm;
import ninja.samryecroft.returnhome.tracker.user.dto.EditUserForm;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The rule is not only correct, it is CONNECTED - to both forms, through the real validator.
 *
 * <p>{@link PasswordPolicyTest} proves the rule. It cannot prove the rule ever runs: a class-level
 * constraint that is not picked up, or a validator Spring cannot construct, leaves a policy that is
 * perfect in isolation and absent in production. That is the failure this class exists for, and it
 * matters here more than usual because <strong>the thing on the other side of the missing check is a
 * 500, not a weak password</strong> - Spring Security 7.1.0's encoder throws above 72 bytes rather
 * than truncating, so before T272 any over-long password on admin user-create or user-edit produced
 * an error page.
 *
 * <p>Both forms are asserted, because the rule lived in two places before this and the whole point
 * of R4 is that it now lives in one. A test naming only one form would go green on a change that
 * reconnected one and dropped the other - which is the drift the duplication caused in the first place.
 */
@SpringBootTest
class PasswordPolicyIsWiredToTheFormsTest extends AbstractIntegrationTest {

    /** Over 72 BYTES and over 12 characters, so only the byte ceiling can reject it. */
    private static final String OVER_THE_ENCODER_CEILING = "a".repeat(100);

    @Autowired
    private Validator validator;

    @Test
    void anOverLongPasswordOnCreateIsAFieldErrorRatherThanAnEncoderCrash() {
        CreateUserForm form = new CreateUserForm();
        form.setUsername("newstarter");
        form.setEmail("new.starter@example.org");
        form.setPassword(OVER_THE_ENCODER_CEILING);

        assertThat(violationsOn(validator.validate(form), "password"))
                .as("no violation means this reaches BCryptPasswordEncoder.encode(), which throws")
                .isNotEmpty()
                .allSatisfy(message -> assertThat(message).contains("72 bytes"));
    }

    @Test
    void anOverLongPasswordOnEditIsAFieldErrorRatherThanAnEncoderCrash() {
        EditUserForm form = new EditUserForm();
        form.setEmail("existing@example.org");
        form.setNewPassword(OVER_THE_ENCODER_CEILING);

        assertThat(violationsOn(validator.validate(form), "newPassword"))
                .isNotEmpty()
                .allSatisfy(message -> assertThat(message).contains("72 bytes"));
    }

    /** And the ordinary case still gets through, so the guard is not simply refusing everything. */
    @Test
    void anAcceptablePasswordRaisesNoPasswordViolation() {
        CreateUserForm form = new CreateUserForm();
        form.setUsername("newstarter");
        form.setEmail("new.starter@example.org");
        form.setPassword("winter kettle marble lamp");

        assertThat(violationsOn(validator.validate(form), "password")).isEmpty();
    }

    private static <T> Set<String> violationsOn(Set<ConstraintViolation<T>> violations, String field) {
        return violations.stream()
                .filter(violation -> field.equals(violation.getPropertyPath().toString()))
                .map(ConstraintViolation::getMessage)
                .collect(java.util.stream.Collectors.toSet());
    }
}

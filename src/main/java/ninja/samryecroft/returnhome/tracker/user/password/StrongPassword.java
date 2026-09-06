package ninja.samryecroft.returnhome.tracker.user.password;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies {@link PasswordPolicy} to a form (T272 R4).
 *
 * <p>A CLASS-level constraint, not a field one, because the rule is not only about the password: it
 * also refuses passwords built out of the account's own username, email and organisation, and a
 * field validator can only see the field. The error is still reported against the password field,
 * so the form renders it where the person is typing.
 *
 * <p>It carries no {@code min}/{@code max} attributes on purpose. Those would be a second place the
 * numbers live, and two copies of a rule is how one of them stops being true - which is the defect
 * this replaced ({@code @Size(min = 8)}, in two forms, drifting independently).
 */
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

    String message() default "That password does not meet the password policy";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

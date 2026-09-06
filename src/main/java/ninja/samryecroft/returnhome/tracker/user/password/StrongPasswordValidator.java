package ninja.samryecroft.returnhome.tracker.user.password;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;

/**
 * Runs {@link PasswordPolicy} over a {@link PasswordCandidate} and reports the violation against the
 * form's password field.
 *
 * <p>It resolves the organisation NAME from the id the form carries, which is why this is a
 * Spring-managed validator with a repository: the ruling's context values are names, and a form only
 * knows an id. An id that resolves to nothing is not an error here - that is the organisation
 * field's own validation to fail, and this constraint must not invent a second, differently-worded
 * complaint about it.
 */
public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, PasswordCandidate> {

    private final PasswordPolicy policy;
    private final OrganisationRepository organisationRepository;

    public StrongPasswordValidator(PasswordPolicy policy, OrganisationRepository organisationRepository) {
        this.policy = policy;
        this.organisationRepository = organisationRepository;
    }

    @Override
    public boolean isValid(PasswordCandidate candidate, ConstraintValidatorContext context) {
        if (candidate == null) {
            return true;
        }
        Optional<String> rejection = policy.rejectionFor(candidate.passwordBeingSet(),
                new PasswordContext(candidate.usernameForPolicy(), candidate.emailForPolicy(),
                        organisationName(candidate.organisationIdForPolicy())));
        if (rejection.isEmpty()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(rejection.get())
                .addPropertyNode(candidate.passwordFieldName())
                .addConstraintViolation();
        return false;
    }

    private String organisationName(Long organisationId) {
        return organisationId == null ? null
                : organisationRepository.findById(organisationId).map(Organisation::getName).orElse(null);
    }
}

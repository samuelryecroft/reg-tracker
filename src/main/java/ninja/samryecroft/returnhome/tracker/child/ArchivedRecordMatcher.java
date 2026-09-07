package ninja.samryecroft.returnhome.tracker.child;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.HomeScope;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.stereotype.Service;

/**
 * Whether the young person being added already has an ARCHIVED record this person could reach
 * (T328).
 *
 * <p>Archiving takes a record off the lists but leaves it retrievable (T170). So a member of staff
 * who adds the same young person again gets a SECOND record, and the child's history splits in two -
 * interviews under one, the new work under the other - with nothing on any screen saying so. The
 * picker cannot show them the archived one, so nothing they can see tells them it exists.
 *
 * <p><b>An ACTIVE duplicate is deliberately not this class's business.</b> An active record is in
 * the picker, so the person adding a second one has been shown the first and chosen past it - a
 * different problem with a different answer. This exists for the case the screen cannot show.
 *
 * <h2>Why this is done in memory rather than as a query, which is not a shortcut</h2>
 *
 * <p><b>The match key is encrypted, and the encryption is non-deterministic.</b> {@code FieldCipher}
 * is AES-256-GCM with a fresh random IV per value, so the same surname encrypts to a different
 * string every time it is written. {@code where last_name_enc = ?} cannot match, ever - not as an
 * optimisation problem but as a correctness one: it would return no rows and read exactly like "no
 * duplicate exists", which is the silent wrong answer this whole card is about.
 *
 * <p>So candidates are loaded and compared after decryption. The cost is bounded by the number of
 * children the principal can see, on a form submission rather than a hot path. <b>The obvious
 * narrowing was rejected on purpose:</b> {@code last_name_initial} is an unencrypted column and
 * would cut the candidate set by roughly twenty-six, but a row whose initial was never populated
 * would then be skipped - and a skipped row is a missed match, which fails in the silent direction.
 * A slower check that cannot miss beats a faster one that can.
 *
 * <h2>The scope is the whole point, and it is single-sourced</h2>
 *
 * <p>Unscoped, this turns {@code /children/new} into an oracle: submit a name and a date of birth,
 * and the form tells you whether that young person exists anywhere on the platform. That is Article
 * 9 data answered to anyone who may add a child.
 *
 * <p><b>Visibility is decided by {@link HomeScope} and nothing else.</b> That is the access
 * service's own answer, documented as the same decision {@code canViewHome} makes, so this cannot
 * drift into a second opinion about who sees what. <b>{@code homeIdsFor} would have been the
 * obvious thing to reach for and would have been wrong:</b> it returns only the homes a user is
 * directly ATTACHED to, so a care-provider org admin - who has no home attachments and can see every
 * home in their organisation - would have been scoped to nothing and had no check run at all.
 * Silently, for the one role that can also perform the remedy.
 *
 * <p><b>Out of scope means silent, not refused</b> (Creed's ruling). A block is itself a weak
 * oracle - a refusal reveals existence just as an answer does - so a matching archived record in a
 * home this principal cannot see produces no match and the create proceeds normally. The harm this
 * card names is a history split inside one home; blocking across organisations would leak in
 * exchange for nothing.
 *
 * <p><b>NEVER LET A SAFETY CHECK ANSWER A QUESTION THE ASKER WAS NOT ENTITLED TO ASK.</b>
 *
 * <h2>What is deliberately not decided here</h2>
 *
 * <p>A cross-organisation match visible to a platform admin is still reported, because the ruling
 * scopes to what the principal may see and says nothing narrower. There is an argument for excluding
 * it - records in two organisations are held by two controllers and encrypted under two different
 * keys, so "the same young person" is a claim about two separate sets of records rather than one
 * split history - but that is a product judgement nobody has made, and inventing it here would
 * narrow a safeguarding check on my own authority. Flagged rather than absorbed.
 */
@Service
public class ArchivedRecordMatcher {

    private final ChildRepository childRepository;
    private final HomeRepository homeRepository;
    private final OrganisationAccessService organisationAccessService;

    public ArchivedRecordMatcher(ChildRepository childRepository, HomeRepository homeRepository,
            OrganisationAccessService organisationAccessService) {
        this.childRepository = childRepository;
        this.homeRepository = homeRepository;
        this.organisationAccessService = organisationAccessService;
    }

    /**
     * An archived record for this young person that {@code principal} may already reach, if there is
     * one.
     *
     * @param lastName    trimmed and compared case-insensitively. <b>The key is last name and date of
     *                    birth, and deliberately not first name:</b> that is where typing varies most
     *                    (Katie/Kathryn, shortened forms) and a first-name mismatch would let the
     *                    duplicate straight through, which is the failure this exists to close. Nor
     *                    the local case reference, which is optional and so cannot be a key at all.
     */
    public Optional<Child> archivedMatchFor(String lastName, LocalDate dateOfBirth, AppUserPrincipal principal) {
        if (lastName == null || lastName.isBlank() || dateOfBirth == null || principal == null) {
            return Optional.empty();
        }
        HomeScope scope = organisationAccessService.homeScopeFor(principal);
        List<Long> visibleHomeIds = homeRepository.findAllWithOrganisation().stream()
                .filter(scope::canView)
                .map(Home::getId)
                .toList();
        if (visibleHomeIds.isEmpty()) {
            return Optional.empty();
        }
        // findByHomeIdIn rather than the ...AndArchivedAtIsNull variant beside it: the archived rows
        // ARE the subject here, and reaching for the list query out of habit would return exactly the
        // records that are not the problem.
        return childRepository.findByHomeIdIn(visibleHomeIds).stream()
                .filter(Child::isArchived)
                .filter(candidate -> sameName(candidate.getLastName(), lastName))
                .filter(candidate -> dateOfBirth.equals(candidate.getDateOfBirth()))
                .findFirst();
    }

    private static boolean sameName(String stored, String submitted) {
        return stored != null && stored.trim().equalsIgnoreCase(submitted.trim());
    }
}

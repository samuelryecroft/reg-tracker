package ninja.samryecroft.returnhome.tracker.organisation;

/**
 * What an organisation is: the supplier who provides the service, or the care provider whose
 * children's records it holds.
 *
 * <p><b>Set once, at creation, and never changed - and that is load-bearing, not merely how things
 * happen to be.</b> {@code setType} is called only when an organisation is created (the admin form
 * and the demo seeder); there is deliberately no edit-organisation endpoint. Three separate rules
 * read this value as fixed, and each breaks silently if it stops being:
 *
 * <ul>
 *   <li>{@code RoleMatrix.isCareProviderOrgAdmin} reads the type from the SIGNED-IN PRINCIPAL, while
 *       {@code HomeAdminController} loads the organisation fresh by id. Two sources for one fact,
 *       which agree only because the fact cannot change: retyping an organisation would leave every
 *       existing session holding powers its organisation no longer confers.</li>
 *   <li>{@code HomeAdminController} refuses a home under a SUPPLIER, which is what makes it true
 *       that every encrypted record resolves to a care provider.</li>
 *   <li>{@link OrganisationLifecycleService} requires a KEK only for a CARE_PROVIDER, relying on the
 *       point above.</li>
 * </ul>
 *
 * <p><b>If an edit-organisation screen is ever added, these are what it has to deal with</b> - and
 * Kevin's point about where the pressure will come from is worth keeping: the likeliest reason
 * anyone builds one is to correct a typo during onboarding ("wrong type, let me fix it"), which is
 * exactly the moment it would do the most damage. Retyping SUPPLIER to CARE_PROVIDER is the
 * dangerous direction: that organisation is already ACTIVE, having been activated down the branch
 * that skips the key check, so it has no KEK and nothing would notice. It would sail past the
 * activation gate - the gate gets asked about STATUS, and the thing that changed is TYPE - and fail
 * closed on its first encrypted write. That is the T168(b) incident arriving through the one door
 * the gate cannot see.
 *
 * <p>None of that is a defect today, because the edit screen does not exist. It is written here so
 * that building one is a decision rather than an accident.
 */
public enum OrgType {
    SUPPLIER,
    CARE_PROVIDER
}

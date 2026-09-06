package ninja.samryecroft.returnhome.tracker.user;

public enum Role {
    HOME_STAFF("Home Staff"),
    ORG_ADMIN("Org Admin"),
    COORDINATOR("Coordinator"),
    VISITOR("Visitor"),
    REVIEWER("Reviewer"),
    VIEWER("Viewer"),
    ADMIN("Admin");

    private final String displayName;

    Role(String displayName) {
        this.displayName = displayName;
    }

    /**
     * The label a person reads, matching {@link
     * ninja.samryecroft.returnhome.tracker.interview.InterviewStatus#getDisplayName()}.
     *
     * <p>T119 4d renders roles as chips reading "Home Staff", not "HOME_STAFF". Written as a stored
     * label rather than derived from {@code name()} by replacing underscores and title-casing,
     * because that derivation gets "Org Admin" wrong the moment anyone expects "Organisation
     * Admin", and a rule that is right for six values and wrong for the seventh is harder to spot
     * than seven literals. The enum NAME stays the wire and CSS identity - templates that need a
     * stable token still use {@code ${r}}, never this.
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * ORG_ADMIN, COORDINATOR, VISITOR and REVIEWER are all facets of the same organisation-based
     * account and can be freely combined with each other. HOME_STAFF (tied to a home, not an
     * organisation) and ADMIN (the platform account, tied to neither) are solo roles - a user with
     * either of those cannot hold any other role at the same time. VIEWER is also org-scoped (tied
     * to a Care Provider org) but is intentionally excluded here - see
     * {@link ninja.samryecroft.returnhome.tracker.user.UserService#validateRoles} for why it can't
     * be combined with the Supplier-side roles in this set even though both are "org-scoped."
     */
    public boolean isOrgScoped() {
        return this == ORG_ADMIN || this == COORDINATOR || this == VISITOR || this == REVIEWER;
    }
}

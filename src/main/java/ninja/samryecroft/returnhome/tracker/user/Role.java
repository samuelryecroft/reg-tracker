package ninja.samryecroft.returnhome.tracker.user;

public enum Role {
    HOME_STAFF,
    ORG_ADMIN,
    COORDINATOR,
    VISITOR,
    REVIEWER,
    VIEWER,
    ADMIN;

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

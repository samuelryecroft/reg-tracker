package ninja.samryecroft.returnhome.tracker.organisation;

import ninja.samryecroft.returnhome.tracker.home.Home;

/**
 * One principal's home visibility, resolved up front so a list can be filtered without a query per
 * row. Obtain it from {@link OrganisationAccessService#homeScopeFor}.
 *
 * <p>An interface rather than a concrete type so callers can be tested against a stub, and so the
 * delegation stays visible: an exporter that filters by a {@code HomeScope} is demonstrably using
 * the access service's answer rather than reimplementing scoping in its own stream.
 */
@FunctionalInterface
public interface HomeScope {

    boolean canView(Home home);
}

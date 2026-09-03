package ninja.samryecroft.returnhome.tracker.child;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Child names are encrypted (COLUMN-ENCRYPTION-OPTIONS.md tier 2), which is why nothing here orders
 * by them any more. Ciphertext sorts randomly, so the database cannot do it; {@link #BY_NAME} sorts
 * in the application after the {@code @PostLoad} decryption instead.
 *
 * <p>The real cost is not the sort - a home's children are a few hundred rows at most - it is that
 * DB-side {@code LIMIT}/{@code OFFSET} paging over a name order is now impossible: page three
 * cannot be produced without decrypting everything first. There is no pagination in the code today,
 * so nothing breaks, but it is a deliberate ceiling and worth knowing before someone adds paging
 * and finds it does not work.
 *
 * <p>Every list query fetches {@code home}, and that is load-bearing rather than an optimisation:
 * decryption resolves the owning organisation through the home, so without the join each row would
 * trigger its own select.
 */
public interface ChildRepository extends JpaRepository<Child, Long> {

    /** Application-side replacement for the {@code ORDER BY} the encrypted columns took away. */
    Comparator<Child> BY_NAME = Comparator
            .comparing(Child::getLastName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(Child::getFirstName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

    /** Grouped by home first, which the database can still do, then by name in memory. */
    Comparator<Child> BY_HOME_THEN_NAME = Comparator
            .comparing((Child child) -> child.getHome() == null ? "" : child.getHome().getName(),
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(BY_NAME);

    @EntityGraph(attributePaths = "home")
    List<Child> findByHomeId(Long homeId);

    @EntityGraph(attributePaths = "home")
    @Query("select c from Child c")
    List<Child> findAllWithHome();

    @EntityGraph(attributePaths = "home")
    @Query("select c from Child c where c.home.organisation.id = :organisationId")
    List<Child> findByHomeOrganisationIdWithHome(@Param("organisationId") Long organisationId);

    @EntityGraph(attributePaths = {"home", "home.organisation"})
    @Query("select c from Child c where c.id = :id")
    Optional<Child> findDetailedById(@Param("id") Long id);

    @EntityGraph(attributePaths = "home")
    @Query("select c from Child c where c.home.id in (select h.id from User u join u.viewerHomes h where u.id = :userId)")
    List<Child> findByViewerAccess(@Param("userId") Long userId);
}

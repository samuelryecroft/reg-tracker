package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether an organisation already owns rows encrypted under a field key.
 *
 * <p>Asked on the minting path only, so that a key is never created for an organisation whose data
 * was encrypted under a previous one. See {@link OrgFieldKeyStore} for why that case exists at all.
 *
 * <p><b>The paths are written out, and the list of them is CHECKED rather than trusted.</b> Every
 * encrypted entity implements {@link EncryptedEntity} and can name its owning organisation in Java,
 * but each reaches it by a different association - a child through its home, a report through its
 * request's home - and a query needs the path, which no interface can supply. So the mapping is
 * explicit, and {@code EncryptedDataProbeCoverageTest} fails the build if a new
 * {@code EncryptedEntity} appears without one. <b>An exception that is merely declared is a hole;
 * one that is checked is a rule.</b>
 */
@Component
public class EncryptedDataProbe {

    /**
     * Entity name to the path from it to the owning organisation's id.
     *
     * <p>Ordered cheapest-first only as a courtesy; correctness does not depend on it, because any
     * one match is enough to refuse.
     */
    private static final Map<String, String> ORGANISATION_PATHS = Map.of(
            "Child", "home.organisation.id",
            "InterviewRequest", "home.organisation.id",
            "InterviewReport", "interviewRequest.home.organisation.id");

    private final EntityManager entityManager;

    public EncryptedDataProbe(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    static List<String> coveredEntityNames() {
        return List.copyOf(ORGANISATION_PATHS.keySet());
    }

    /**
     * Whether this organisation already holds any encrypted row.
     *
     * <p><b>Runs in its own transaction</b>, so that asking the question cannot be affected by, or
     * affect, whatever business transaction happened to trigger the mint.
     *
     * <p>Throws rather than returning false if it cannot answer. The caller treats "I do not know"
     * as its own outcome, and the reason is in {@link OrgFieldKeyStore}: not knowing is not the same
     * as knowing there is nothing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean organisationHoldsEncryptedRows(long organisationId) {
        for (String entityName : ORGANISATION_PATHS.keySet()) {
            if (holdsRowsOf(entityName, organisationId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One entity's path, asked on its own.
     *
     * <p>Package-private for the correctness test, and the granularity is the point rather than a
     * convenience. {@link #organisationHoldsEncryptedRows} short-circuits on the first match, so a
     * test that only asks the public method <b>cannot attribute a "yes" to the path under
     * examination</b> - a Child row satisfies it whatever the report's path says, and a wrong path
     * passes unnoticed. A correctness check has to ask each path by name.
     */
    boolean holdsRowsOf(String entityName, long organisationId) {
        Long found = entityManager
                .createQuery("select count(e) from " + entityName + " e where e."
                        + ORGANISATION_PATHS.get(entityName) + " = :organisationId", Long.class)
                .setParameter("organisationId", organisationId)
                .setMaxResults(1)
                .getSingleResult();
        return found != null && found > 0;
    }
}

package ninja.samryecroft.returnhome.tracker.organisation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.springframework.stereotype.Service;

/**
 * Gathers the membership {@link OrganisationReadiness} judges (T267).
 *
 * <h2>One gathering, two surfaces</h2>
 *
 * <p>The platform admin's organisation tree asks about every organisation at once; an org admin's
 * user list asks about their own. <b>Both are answered out of the same pair of queries</b>, and
 * that is the point of {@link #readinessByOrganisationId}: two ways of counting the same people is
 * the T281 defect, where a grant rule and a visibility rule described the same accounts, agreed by
 * coincidence, and then one of them moved. Nothing here can drift, because there is only one of it.
 *
 * <p>Asking for the whole platform to answer one organisation's question is affordable rather than
 * lazy: both queries are {@code select distinct} over {@code (organisationId, role)}, so the result
 * is bounded by organisations times roles - single figures per organisation - and not by how many
 * users exist.
 *
 * <h2>Why two queries rather than one</h2>
 *
 * <p>An organisation reaches its people two ways. Everyone org-scoped carries {@code organisation}
 * directly; HOME_STAFF carry none at all and belong through their HOMES. A single query over either
 * column is silently short by somebody, and which somebody depends on which column you picked -
 * exactly the T273 finding. Coalescing the two columns would appear to work today only because a
 * user holding both routes has them pointing at the same organisation, which is a coincidence of
 * the current role rules rather than a property anything enforces.
 *
 * <p>This service holds no authorisation rule and guards nothing: it answers a question about
 * membership, and the caller decides who may ask. That keeps it outside T313's question, which is
 * about where a WHO rule belongs, not where a computed fact does.
 */
@Service
public class OrganisationReadinessService {

    private final UserRepository userRepository;

    public OrganisationReadinessService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Readiness for each of the given organisations, keyed by id.
     *
     * <p>Every organisation passed in gets an entry, including ones with nobody in them at all.
     * An absent key would make a caller choose a default, and the natural default when a map lookup
     * misses is "nothing is wrong" - which is the answer that is always false here, since an
     * organisation with no users is the one this whole card exists to point at.
     */
    public Map<Long, OrganisationReadiness> readinessByOrganisationId(List<Organisation> organisations) {
        Map<Long, Set<Role>> present = new HashMap<>();
        collect(userRepository.enabledRolesByOrganisation(), present);
        collect(userRepository.enabledRolesByHomeOrganisation(), present);

        Map<Long, OrganisationReadiness> readiness = new HashMap<>();
        for (Organisation organisation : organisations) {
            readiness.put(organisation.getId(), new OrganisationReadiness(organisation.getType(),
                    present.getOrDefault(organisation.getId(), Set.of())));
        }
        return readiness;
    }

    /** Readiness for one organisation, out of the same gathering the tree uses. */
    public OrganisationReadiness readinessFor(Organisation organisation) {
        return readinessByOrganisationId(List.of(organisation)).get(organisation.getId());
    }

    private void collect(List<Object[]> rows, Map<Long, Set<Role>> into) {
        for (Object[] row : rows) {
            // A home with no organisation is possible in the schema, and the join then yields a
            // null key. Dropped rather than grouped under null: it belongs to no organisation, so
            // it can make no organisation operational.
            if (row[0] == null) {
                continue;
            }
            into.computeIfAbsent((Long) row[0], key -> new HashSet<>()).add((Role) row[1]);
        }
    }
}

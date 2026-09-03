package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgFieldKeyRepository extends JpaRepository<OrgFieldKey, Long> {

    Optional<OrgFieldKey> findByOrganisationId(Long organisationId);
}

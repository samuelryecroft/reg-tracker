package ninja.samryecroft.returnhome.tracker.theme;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ThemeSettingsRepository extends JpaRepository<ThemeSettings, Long> {

    Optional<ThemeSettings> findByOrganisationId(Long organisationId);

    Optional<ThemeSettings> findByOrganisationIsNull();
}

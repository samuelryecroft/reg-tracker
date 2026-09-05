package ninja.samryecroft.returnhome.tracker.theme;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;

@Entity
@Table(name = "theme_settings")
public class ThemeSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null means the platform default: used for the platform ADMIN role and as a fallback. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_id")
    private Organisation organisation;

    @Column(name = "primary_color", nullable = false)
    private String primaryColor;

    @Column(name = "secondary_color", nullable = false)
    /**
     * <b>T186: written, never read.</b> Nothing derives anything from this any more - the inline
     * per-org {@code <style>} block that consumed it is gone and branding travels as a hue.
     *
     * <p>It is still SET on insert because the column is {@code VARCHAR(7) NOT NULL} (V4), so an
     * insert that omits it fails - including demo seeding and platform-default creation, i.e.
     * startup on a fresh database. <b>Do not remove the setter calls without the migration.</b>
     * Dropping the column is a separate card on purpose: an accessibility fix should not carry
     * irreversible data loss on its critical path.
     */
    private String secondaryColor;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public Organisation getOrganisation() {
        return organisation;
    }

    public void setOrganisation(Organisation organisation) {
        this.organisation = organisation;
    }

    public String getPrimaryColor() {
        return primaryColor;
    }

    public void setPrimaryColor(String primaryColor) {
        this.primaryColor = primaryColor;
    }

    public String getSecondaryColor() {
        return secondaryColor;
    }

    public void setSecondaryColor(String secondaryColor) {
        this.secondaryColor = secondaryColor;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

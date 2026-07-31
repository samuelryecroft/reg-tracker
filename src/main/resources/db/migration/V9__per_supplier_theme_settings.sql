ALTER TABLE theme_settings ADD COLUMN organisation_id BIGINT REFERENCES organisations (id);
ALTER TABLE theme_settings ADD CONSTRAINT uq_theme_settings_organisation_id UNIQUE (organisation_id);

CREATE SEQUENCE theme_settings_id_seq OWNED BY theme_settings.id;
SELECT setval('theme_settings_id_seq', (SELECT max(id) FROM theme_settings));
ALTER TABLE theme_settings ALTER COLUMN id SET DEFAULT nextval('theme_settings_id_seq');

-- Row id=1 (organisation_id stays NULL) becomes the platform default: used for the platform ADMIN
-- role, and as a fallback if a Supplier org somehow ends up without its own theme row. Every
-- existing Supplier org gets its own theme row, cloned from that default, so branding (web UI and
-- generated reports alike) can now diverge per Supplier org, e.g. STEPS with Children vs Greyhams
-- Consulting - each supplier's own look follows their staff and their client Care Providers.
INSERT INTO theme_settings (primary_color, secondary_color, organisation_id, updated_at)
SELECT t.primary_color, t.secondary_color, o.id, now()
FROM organisations o
CROSS JOIN (SELECT primary_color, secondary_color FROM theme_settings WHERE id = 1) t
WHERE o.type = 'SUPPLIER';

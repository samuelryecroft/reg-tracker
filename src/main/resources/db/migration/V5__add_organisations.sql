CREATE TABLE organisations (
    id                       BIGSERIAL PRIMARY KEY,
    name                     VARCHAR(255) NOT NULL,
    type                     VARCHAR(30) NOT NULL,
    supplier_organisation_id BIGINT REFERENCES organisations (id),
    created_at               TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_organisations_supplier_organisation_id ON organisations (supplier_organisation_id);

-- Seed a default Supplier + Care Provider pair so existing dev data (homes, coordinator1,
-- contractor1) has somewhere to land when later migrations backfill their new organisation FKs.
INSERT INTO organisations (id, name, type) VALUES (1, 'STEPS with Children', 'SUPPLIER');
INSERT INTO organisations (id, name, type, supplier_organisation_id) VALUES (2, 'Default Care Provider', 'CARE_PROVIDER', 1);

SELECT setval('organisations_id_seq', 2, true);

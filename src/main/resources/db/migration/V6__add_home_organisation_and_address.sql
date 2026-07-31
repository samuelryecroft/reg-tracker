ALTER TABLE homes ADD COLUMN organisation_id BIGINT;
UPDATE homes SET organisation_id = 2 WHERE organisation_id IS NULL;
ALTER TABLE homes ALTER COLUMN organisation_id SET NOT NULL;
ALTER TABLE homes ADD CONSTRAINT fk_homes_organisation FOREIGN KEY (organisation_id) REFERENCES organisations (id);
CREATE INDEX idx_homes_organisation_id ON homes (organisation_id);

ALTER TABLE homes
    ADD COLUMN address_line_1 VARCHAR(255),
    ADD COLUMN address_line_2 VARCHAR(255),
    ADD COLUMN address_line_3 VARCHAR(255),
    ADD COLUMN postcode       VARCHAR(20),
    ADD COLUMN what3words     VARCHAR(100);

UPDATE homes SET address_line_1 = address WHERE address IS NOT NULL;

ALTER TABLE homes DROP COLUMN address;

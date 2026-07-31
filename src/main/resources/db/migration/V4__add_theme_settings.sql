CREATE TABLE theme_settings (
    id             BIGINT PRIMARY KEY,
    primary_color  VARCHAR(7) NOT NULL,
    secondary_color VARCHAR(7) NOT NULL,
    updated_at     TIMESTAMP NOT NULL DEFAULT now()
);

INSERT INTO theme_settings (id, primary_color, secondary_color) VALUES (1, '#F36E2A', '#FFF0DD');

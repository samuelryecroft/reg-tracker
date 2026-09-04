-- T138 batch 1b: a per-user appearance preference (light/dark/auto), spec 2.3/R-Q9.
--
-- DEFAULT 'AUTO' for every row, existing and new, per R-Q9's closed reasoning: some people set a
-- dark OS theme for photophobia or migraine and others set light for astigmatism, so honouring
-- whichever choice a user has already made at the OS level is the accessible default. Applies
-- equally to a row that predates this migration and a brand new account - neither should land on
-- Nocturne's own fixed dark-first default without ever having been asked.
--
-- VARCHAR + @Enumerated(EnumType.STRING) at the application layer, not a CHECK constraint or a
-- Postgres ENUM type - the same convention organisations.type already uses (V5).
ALTER TABLE users
    ADD COLUMN appearance_preference VARCHAR(10) NOT NULL DEFAULT 'AUTO';

-- Entra External ID, phase P1 (ENTRA-AUTH-DESIGN.md §6). Schema only: nothing reads or writes
-- idp_subject yet, and form login remains the only live authentication path. This migration is
-- deployable on its own and changes no behaviour.

-- The persistent identity key. Entra's `sub` (or `oid`) claim, and the ONLY thing a login is ever
-- allowed to link on: email is mutable and reassignable, so binding identity to it means the next
-- holder of a recycled address silently inherits the previous holder's access. In a system holding
-- children's safeguarding records that is an unauthorised disclosure, not a login bug.
--
-- Nullable because it is written once, at the one-time link on first Entra login (P4), and every
-- account is created before that by an ORG_ADMIN - the system is invite-only, never just-in-time.
ALTER TABLE users ADD COLUMN idp_subject VARCHAR(255);

-- Unique-when-present: Postgres permits many NULLs under a UNIQUE constraint, so this constrains
-- linked rows without requiring the unlinked ones to invent a value. It stops two application
-- accounts binding to one Entra identity. The other half of that guarantee - that one account
-- cannot be re-bound to a second identity - is the `idp_subject IS NULL` clause in P4's link query,
-- which this column's nullability is what makes expressible.
ALTER TABLE users ADD CONSTRAINT uq_users_idp_subject UNIQUE (idp_subject);

-- Credentials become optional, ahead of the Add-User screen that stops collecting them.
--
-- This is not part of the Entra cutover itself, but it belongs in the same migration rather than a
-- second one: `password` is NOT NULL today, so the moment a user can be created without a
-- credential the insert fails. Existing rows keep their password and form login keeps working for
-- them, which is what the cutover sequence depends on - form login stays the live path until an
-- ADMIN has been proven able to sign in through Entra (§8), and break-glass keeps a local
-- credential after that (§5 D2).
--
-- Dropping the column entirely is P8, deliberately last and after cutover has been lived with.
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;

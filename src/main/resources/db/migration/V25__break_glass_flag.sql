-- T344: the emergency account becomes a FACT IN THE DATABASE rather than a string in a config file.
--
-- WHY THIS EXISTS, and it is not housekeeping. Until now the most privileged permanent exception in
-- the system - the one account exempt from the second factor - was chosen by comparing
-- app.admin.username to a row. ADMIN_SEED_USERNAME was never set in production, so that property
-- resolved to its default, the literal string 'admin', and ANY account named 'admin' was permanently
-- exempt from MFA. Nobody decided that; it was the default value of an environment variable nobody
-- set (T341).
--
-- A flag cannot be collided with by naming. It also survives usernames ceasing to be login
-- identifiers, which is the rest of this release.
ALTER TABLE users ADD COLUMN is_break_glass BOOLEAN NOT NULL DEFAULT FALSE;

-- At most one. A second emergency account is not a redundancy, it is a second permanent exception
-- that nobody is watching - and the partial predicate means the index constrains only the TRUE rows,
-- leaving every ordinary account unconstrained.
CREATE UNIQUE INDEX idx_users_single_break_glass ON users (is_break_glass) WHERE is_break_glass;

-- Login moves to the email address, so a person no longer needs a username at all. The column stays
-- (see below) but stops being required.
--
-- WIDENING, so the deploy window is safe in the direction that matters: the OLD jar briefly meets
-- this schema and always writes a username, which a nullable column accepts. The reverse - dropping
-- the column - would break that old jar outright, which is one of the reasons it is not being done.
ALTER TABLE users ALTER COLUMN username DROP NOT NULL;

-- THE RULE, STATED AS A RULE. T264 declined a blunt NOT NULL on email and was right to: it would
-- have forced a mailbox onto the single identity we least want to give one, and the emergency
-- account must never depend on the mail channel its exemption exists to survive.
--
-- The actual invariant was never "every row has an address". It is "every row that signs in with an
-- address has one" - and that is expressible. This permits exactly one shape of null and forbids the
-- one that would leave a person unable to log in.
--
-- IT FAILS CLOSED if any row violates it, deliberately and in the same spirit as V24: a migration
-- must never invent an address to make itself pass.
ALTER TABLE users ADD CONSTRAINT users_email_or_break_glass
    CHECK (is_break_glass OR email IS NOT NULL);

-- T322 follow-up: mark when an account's email address was first proven DELIVERABLE.
--
-- WHAT THIS PROVES, AND THE LIMIT IS THE POINT. It proves somebody received a code at this address.
-- It does NOT prove the address belongs to the person whose account it is. That distinction is not
-- pedantry: this column was originally specified as a control against "whoever created the account
-- chose where the first code goes", and it CANNOT do that job - the confirmation is delivered TO
-- THAT ADDRESS, so a creator who set a mailbox they control simply completes it.
--
-- (That threat turned out not to need a control anyway: whoever may create an account sets its
-- password and its address in the same form, so they can already sign in as that person. The case
-- that IS an escalation - a manager setting an existing colleague's password - is closed by T323
-- making an address change platform-admin-only, so the code goes to the real person's mailbox.)
--
-- WHAT IT IS ACTUALLY FOR, which is a likely event rather than an adversary: a MISTYPED address.
-- Without this, a typo means sign-in codes for a children's-services system are posted to whoever
-- owns the mistyped mailbox, once per attempt, indefinitely, while the real user reports only that
-- "the codes never arrive". This bounds that to a small number of messages and turns it into a
-- visible state an administrator can act on.
--
-- NULLABLE, and no backfill. Every existing row starts unverified, which is correct rather than
-- inconvenient: we have never proven any of these addresses receives mail, and the first successful
-- code proves it without anyone doing anything. Backfilling a value would be asserting exactly the
-- fact this column exists to record.
--
-- NO DEPLOY-WINDOW PROBLEM (cf. V19/V21): the DB plane runs Flyway to completion before the new jar
-- is swapped in, so the OLD jar briefly meets this schema - and a nullable column it never writes is
-- inert to it. This is deliberately NOT bundled with the email UNIQUE + NOT NULL change, which is
-- DDL against existing data and goes to the production gate on its own.

ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMP;

-- Counts challenges sent to an address that has never been proven deliverable. It is what bounds the
-- leak: without a ceiling, a wrong address receives a code on every sign-in attempt forever.
ALTER TABLE users ADD COLUMN unverified_challenge_count INTEGER NOT NULL DEFAULT 0;

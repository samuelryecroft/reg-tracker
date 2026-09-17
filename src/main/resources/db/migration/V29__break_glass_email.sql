-- T364 stage 2: give the break-glass emergency account a reserved-domain email address, so it
-- authenticates by email like every other account and no longer depends on the username column.
--
-- WHY A RESERVED DOMAIN, AND WHY THIS ONE. break-glass is the most privileged permanent account in
-- the system and it is exempt from the second factor (SecondFactorPolicy.isEmergencyExempt), so it
-- never receives a code and its address is never delivered to. A PLAUSIBLE address on a real domain
-- would therefore be account-takeover bait: someone could register it later and own the fire exit.
-- .invalid is reserved by RFC 6761 - it can never resolve and can never be registered - so this
-- address is guaranteed to belong to nobody, forever. It keeps the property the code already relies
-- on ("no address to phish", SecondFactorPolicy) while removing the need for a non-email identity.
-- The local part is self-describing on purpose: it is what the audit trail names this account by
-- once the login identifier becomes the email (User.getLoginIdentifier).
--
-- MUST MATCH AdminUserSeeder.BREAK_GLASS_EMAIL, which sets the same value on a FRESH install. This
-- migration is for the EXISTING production row, which the seeder cannot touch: the seeder only ever
-- creates the FIRST admin and never updates an existing one, so a break-glass row already in the
-- database (every deployed environment) can only be re-homed here.
--
-- ONLY the null-email break-glass row is touched. If an administrator has already given the account
-- a real address, that address is left alone - it can already sign in by email, which is the whole
-- goal - and this WHERE clause makes the migration safe to re-run.
--
-- SAFE IN THE DEPLOY WINDOW, in the direction that matters. db-plane runs this BEFORE the jar swap,
-- so the new jar (which resolves break-glass by email) always meets a row that already has one. The
-- OLD jar, briefly meeting this row between the migration and the swap, still resolves break-glass
-- by its USERNAME (its getLoginIdentifier is `breakGlass ? username : email`, and AppUserDetailsService
-- still falls back to the username), so setting an email does not disturb it. The username column is
-- deliberately left in place - dropping it is stage 3 and Sam's own decision.
--
-- NOT AUTHORISED TO RUN WITHOUT SAM'S GO. This is a data change to the emergency account on a
-- safeguarding product; it is a proposal reviewed before it goes near production, applied at the
-- db-plane run, not by merging this file.
UPDATE users
   SET email = 'break-glass@return-home.invalid'
 WHERE is_break_glass
   AND email IS NULL;

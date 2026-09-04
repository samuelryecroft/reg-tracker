-- T168(b), the contract half of V19's expand/contract.
--
-- V19 added organisations.status WITH a default so the migration stayed compatible with the jar
-- that was still serving while it ran. This removes that default now that every writer sets the
-- column explicitly.
--
-- WHY THE DEFAULT MUST NOT SURVIVE: 'ACTIVE' as a column default fails OPEN in the one direction
-- that matters. Any future insert that forgets to set a status would silently produce a USABLE
-- organisation - one that may hold children's records without its encryption key having ever been
-- verified - and nothing would fail. With no default it is a NOT NULL violation: loud, immediate,
-- and impossible to ship past. Organisation.status initialising to PENDING in Java covers the
-- ordinary path; this covers everything that bypasses the entity.
--
-- WHY IT SHIPS IN THE SAME PULL REQUEST AS V19 RATHER THAN AS A FOLLOW-UP TICKET. The objection to
-- splitting was that a deferred contract migration gets forgotten, permanently retaining the
-- fail-open default the split exists to remove - which is a fair objection to a PROMISE. It is not
-- one here, because this file already exists: Flyway applies it on the next migration run whether
-- or not anybody remembers it. If the deploy runs the migrator once more after the new jar is live,
-- the window closes immediately; if it does not, the next deploy closes it. Neither path depends on
-- human memory, which is the property the objection was actually about.

ALTER TABLE organisations
    ALTER COLUMN status DROP DEFAULT;

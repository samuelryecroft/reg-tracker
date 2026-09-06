# Column-level encryption — options and recommendation

**Status:** exploration + recommendation for god and the human. Read-only design — no code, no
schema change, no spend. Nothing here is built.
**Inputs:** `THREAT-MODEL.md`, `DOCUMENT-ENCRYPTION-DESIGN.md`, `DOCUMENT-KEYS.md`, `AUDIT-PLAN.md`,
and the actual entity/repository code (`Child`, `InterviewRequest`, `InterviewReport`, `User` and
their repositories) — the query analysis in §2 is taken from the code, not assumed.

**Headline:** there is an unusually good deal available here. The most sensitive data in the system —
the child's own account of why they went missing, what happened to them, and the safeguarding
concerns arising — sits in ~25 free-text columns that **no query ever filters, sorts or searches on**.
Those can be encrypted at essentially zero functional cost. The fields that *are* expensive to
encrypt (names, for sorting) are markedly less sensitive than the narrative. Phase 1 should take the
free deal; phase 2 is a genuine product trade-off for the human.

---

## 1. What we already have, and what this adds

| Control | Shipped | Closes |
|---|---|---|
| TDE at rest (platform-managed) | yes | theft of the physical disk / Azure storage layer |
| Private networking (B2) | yes | reaching Postgres from the internet or another Azure tenant |
| Least-privilege DB roles (WS-G) | yes | the app performing DDL; tampering with `audit_events` |
| Per-org envelope encryption of report `.docx` | yes | blob storage compromise; per-org blast radius on documents |
| Column-level encryption | **not yet** | see §4 |

The gap this closes is specific and worth naming precisely rather than in the abstract: **today,
anyone holding the runtime database password can read every child's record in plaintext.** TDE does
not help — it decrypts transparently for anyone who can connect. That password exists in Terraform
state (see `terraform/bootstrap/README.md`, and `WS-E-DESIGN.md` §5.4), in Key Vault, and in the
App Service configuration. Field-level encryption breaks that single-credential path: a database
credential alone would then yield ciphertext, and an attacker would additionally need the
application's Key Vault identity.

---

## 2. Field tiering — driven by what the code actually queries

I read every repository. The determining fact:

> **`InterviewRequest` and `InterviewReport` are never queried, filtered, sorted or searched by any
> of their free-text or clinical fields.** Every query in `InterviewRequestRepository` selects by id,
> by foreign key (home / organisation / visitor / child / status), and orders by `createdAt`.

That means the entire narrative corpus can be encrypted with randomized encryption and **not one
query, screen or dashboard tile changes**. This is the opposite of the usual column-encryption
situation and it should be exploited.

### Tier 1 — encrypt, no functional cost (~25 columns)

`InterviewRequest`: `notes`, `knownRisks`, `childsComments`, `missingEpisodeDetails`,
`importantPeople`, `aboutYoungPerson`, `socialWorkerDetails`, `policeMfhCoordinatorDetails`,
`legalStatus`, `placingLocalAuthority`.

`InterviewReport`: `ifNotWhyLate`, `consultationWithHomeStaff`, `interviewDeclinedReason`,
`whereWereYouWhileMissing`, `whoWereYouWithWhileMissing`, `whatMadeYouGoMissing`,
`whatCanBeDoneToAddressReasons`, `whatDidYouDoWhileMissing`, `whatHappenedWhenReturned`,
`preventFutureMissingSuggestions`, `additionalCommentsFromYoungPerson`,
`additionalInfoFromParentCarer`, `risksIdentifiedDuringEpisode`, `risksIncreaseFutureEpisodes`,
`safeguardingConcernsToExplore`, `infoToHelpLocateFuture`, `interviewLocation`.

This is the highest-sensitivity content in the database — GDPR Art. 9 special-category data
concerning children, in their own words — and it is queried only by primary key. Best ratio in the
system.

### Tier 2 — encrypt, but it costs something (human product call)

| Field | What breaks | Mitigation |
|---|---|---|
| `Child.firstName`, `Child.lastName` | **4 of the 5 `ChildRepository` queries `ORDER BY c.lastName, c.firstName`** — under randomized encryption that sorts by ciphertext, i.e. randomly | sort in the application after decrypt |
| `Child.dateOfBirth` | no current query uses it; any future age-range filter would break | accept; revisit if age filtering is ever wanted |
| `Child.localCaseReference` | no current query; a future "look up by case ref" would break | blind index (§3.4) if that need appears |
| `User.fullName` | `ORDER BY u.fullName` in three queries | sort in the application |

**The real cost of Tier 2 is not the sort, it is pagination.** Sorting a few hundred rows in memory
is free at this scale (a home's children, or one organisation's). But it forecloses DB-side
`LIMIT`/`OFFSET`: once the sort key is ciphertext, the database cannot produce page 3 without the
application decrypting everything first. There is no pagination in the code today, so nothing breaks
now — but this is a deliberate ceiling on a system I am otherwise trying to keep scalable, and the
human should accept it knowingly rather than meet it later.

### Tier 3 — do NOT encrypt, and here is why

- **`User.username`** — every login does `findByUsername` (equality), and it is the link key to the
  Entra `idp_subject`. Encrypting it forces deterministic encryption or a blind index on the hottest
  path in the system, for a field that is a work email address. Not worth it.
- **All ids, foreign keys, `status`, timestamps** (`createdAt`, `scheduledAt`, `returnedAt`,
  `missingSince`, `interviewDate`) — every query, every dashboard aggregate and the whole
  `DeadlineTracker` depends on them. Encrypting these does not harden the system, it deletes it.
- **The boolean risk flags** (`missingInLast6Months`, `missingFiveTimesIn30Days`,
  `strategyMeetingRequested`, `previouslyMissing`, `consideredSelfMissing`, …) — these do carry
  meaning about a child, but they drive the dashboard counts. Encrypting them breaks every tile. Also
  note a boolean has two possible plaintexts: randomized encryption hides the value, but anyone who
  can see the ciphertext alongside the dashboard totals can often infer it anyway. Low value, high
  cost. **Flag for the human** if they specifically want these covered.
- **`audit_events`** — deliberately excluded. `AUDIT-PLAN.md` §B.5 already forbids duplicating report
  content into audit rows, so the sensitive payload is not there in the first place. More
  importantly, the audit trail's value is being an independently readable evidence record: if a
  future investigation needs it and the keys are gone, an encrypted audit log is no evidence at all.
  Encrypting it trades a safeguarding assurance property for very little confidentiality gain.

---

## 3. The options

### 3.1 App-layer envelope encryption reusing the per-org KEK — **recommended**

Reuses exactly what `DOCUMENT-ENCRYPTION-DESIGN.md` already ships: a per-org RSA-2048 KEK in Key
Vault, wrap/unwrap only, the app holding **Key Vault Crypto User** (never create). No parallel key
hierarchy.

**One thing does not carry over, and it matters.** Documents can afford a Key Vault `unwrapKey` per
file because that is one network call per download. A column cannot: a list of 50 children would be
50 round trips to Key Vault, which is both slow and a fail-closed cliff. So the shape must differ:

> **Per-org field DEK, wrapped once, cached in memory.** One AES-256 data key per organisation,
> wrapped by that org's existing KEK and stored (wrapped) in a small `org_field_key` table.
> Unwrapped on first use and held in a bounded, TTL'd in-memory cache. One Key Vault call per org per
> TTL — not per row. Per-row cost is then AES-256-GCM only: microseconds, and irrelevant next to the
> query itself.

Rotation stays cheap for the same reason: rotating an org's KEK re-wraps one data key, it does not
re-encrypt a single row.

**A design trap worth naming.** The obvious implementation — a JPA `AttributeConverter` — does not
actually work for per-org keys. A converter receives only the field value; it has no access to the
entity, so it cannot tell which organisation's key to use. The workable options are (a) do the
crypto in the service layer / `@PrePersist`+`@PostLoad` lifecycle callbacks, where the entity is in
hand and the owning org is resolvable by the same `InterviewRequest → Home → Organisation` path
WS-B already uses for documents; or (b) a request-scoped org context read by the converter, which is
fragile and breaks on batch and async paths. **Recommend (a)** — and note that reusing WS-B's
existing org-resolution keeps the two encryption paths consistent, which matters more than the
convenience of the converter annotation.

A single tenant-wide DEK would let the converter work, but it discards per-org blast-radius
isolation — the property the document design was built around. Not worth the syntactic convenience.

### 3.2 pgcrypto — **reject**

`pgcrypto` does the encryption inside Postgres, which means the key must reach Postgres: as a
literal in the SQL, or in a session variable. Both put key material inside the very system we are
trying to defend against, and both put it somewhere it can be logged — the same `log_statement`
exposure already flagged as `WS-G F2`, where a `ddl`/`all` setting writes statements verbatim into
the server log and onward to Log Analytics.

It closes the "leaked backup" threat, but the key is then in query logs, session state and
potentially the same backup. For a threat model whose main adversary is *someone with database
access*, encrypting with a key you hand to the database is close to circular. Reject.

### 3.3 Azure Postgres CMK (customer-managed TDE key) — **adopt, but not as an answer to this question**

Replaces the platform-managed TDE key with one in our Key Vault. Near-zero effort, no code, no query
impact, and it gives a genuine crypto-shred capability (revoke the key, the server is unreadable).

But be clear about what it does **not** do: TDE — customer-managed or not — decrypts transparently
for anyone who can connect. It does nothing about a leaked `pg_dump`, a rogue read-only grant, or a
compromised database credential. It is a good cheap complement and a bad substitute. Recommend
taking it *because* it is cheap, while recording plainly that it closes almost none of §4.

### 3.4 Deterministic vs randomized

| | Randomized (**recommended**) | Deterministic |
|---|---|---|
| Same plaintext → | different ciphertext each time | identical ciphertext |
| Equality lookup / index | no | yes |
| `ORDER BY`, `LIKE`, range | no | no (only equality) |
| Leaks | length only (pad if it matters) | **equality and frequency** |

Deterministic encryption is much weaker than it looks on this data. On a low-cardinality column it is
close to useless: encrypt `dateOfBirth` deterministically and equal ciphertexts group children by
birthday; encrypt first names and the frequency distribution of English given names identifies the
common ones without any key. On a database of a few hundred children, frequency analysis is not
theoretical.

**Use randomized everywhere.** Where an exact-match lookup is genuinely needed later, add a **blind
index** — a separate column holding `HMAC-SHA256(normalized_value, per-org index key)`. It supports
equality lookup and can be indexed, still leaks equality *within* an org, and — because the key is
per-org — does not allow correlating the same person across organisations. That is the right tool if
"find child by case reference" is ever wanted; do not build it speculatively.

---

## 4. Blast radius — what this honestly buys

**Closes:**
- **A leaked logical backup or restored PITR copy.** `pg_dump` output is plaintext; TDE does not
  cover it. This is the most realistic exposure route for a small team — a backup copied somewhere
  convenient — and Tier 1 makes that dump ciphertext.
- **A compromised database credential.** Today the runtime password alone yields the entire plaintext
  record. This is not hypothetical: that password sits in Terraform state, which `WS-E-DESIGN.md`
  §1a shows is readable by anyone who can trigger a `terraform plan`. After Tier 1, that path yields
  ciphertext and the attacker additionally needs the App Service managed identity's Key Vault access.
- **A rogue or over-broad read-only grant** — a future analyst or reporting user sees ciphertext.
- **Azure operator / storage-layer access**, which CMK also covers.

**Does not close — and this must not be oversold:**
- **A compromised application.** The app necessarily holds both the database connection and the Key
  Vault unwrap right, so anything that executes as the app decrypts everything. This is the same
  ceiling recorded in `DOCUMENT-ENCRYPTION-DESIGN.md`: "only the org can decrypt" is *application
  logic, not cryptography*. Field-level encryption does not change that sentence.
- **Misuse by an authorised user**, including the IDOR class in `THREAT-MODEL.md` — that is
  `OrganisationAccessService`'s job, and no amount of encryption substitutes for it.
- **Data already in existing backups.** Encrypting a column going forward does nothing to the dumps
  already taken. And when the plaintext column is finally dropped, the old values survive in dead
  tuples until a `VACUUM FULL`. Retiring the plaintext is a real piece of work, not a schema line.

In short: it converts "one stolen credential reads every child's record" into "one stolen credential
reads ciphertext". That is a genuine and worthwhile reduction. It is not "the data is now safe".

---

## 4a. Accepted residuals in the built implementation (phase 1, T92/T103)

Two things the shipped scheme does **not** cover. Both were weighed and accepted rather than
overlooked, and they are recorded here so that "encrypted" is not read as more than it is.

**The AAD binds the organisation and the column, but not the row.** Each value is authenticated
against `org=<id>;field=<Entity.field>`, which is what stops ciphertext being moved between
organisations or between columns. It does *not* stop a value being moved between **rows of the same
column within the same organisation** — swapping two children's encrypted first names, say. The
result would decrypt cleanly, because as far as the cipher is concerned nothing is wrong.

Accepted because the attacker already needs **write** access to the database to do it, and someone
with that can corrupt records far more simply than by shuffling ciphertext. Binding the row id would
close it, at the cost of making the value unmovable across a legitimate row rewrite. If it is ever
wanted, the AAD is the only thing that changes.

**The plaintext initials are a weak re-identification vector.** `first_name_initial` +
`last_name_initial`, together with the home and a date, narrow a child considerably in a small
service — a home has a handful of residents, not thousands. This is the deliberate display tradeoff:
without it, no list, tile or page heading can render without unwrapping a key and decrypting every
row, and those screens would fail entirely whenever decryption did.

Accepted as the price of a usable and resilient interface. Worth stating plainly rather than
implying the identifiers are fully protected: the initials are, by design, not.

## 5. Migration of existing rows

Expand/contract, consistent with the WS-G migration model:

1. Add nullable `*_enc` columns (Flyway, as `rht_migrator`).
2. Dual-write: the app writes both, reads plaintext.
3. **Backfill through the application, in batches — not through Flyway.** Only the app can encrypt:
   `rht_migrator` has no Key Vault identity and must never be given one. This is a one-off
   application task, not a migration.
4. Flip reads to `*_enc`; verify; keep dual-write one release for rollback-by-swap.
5. Drop the plaintext columns (Flyway), then `VACUUM FULL` to actually remove the dead tuples.
6. Treat pre-existing backups as containing plaintext for their whole retention period (35 days PITR
   today). They do not become encrypted retroactively.

Steps 5–6 are the ones that get skipped in practice, which is exactly how a system ends up with
"encrypted" columns whose plaintext is still on disk.

---

## 6. Recommendation and phasing

**Phase 1 — Tier 1 free-text corpus. Do this.** ~25 columns, maximum sensitivity, **zero query
breakage**, no screen changes, no product decision needed. Reuses the per-org KEK already shipped.
This is the whole reason the note is worth acting on.

**Phase 1a — enable Postgres CMK.** Cheap, no code, worth taking while Phase 1 is in flight. Record
honestly that it is complementary, not a substitute.

**Phase 2 — Tier 2 child identifiers. Human product call.** Encrypting `firstName`/`lastName`/
`dateOfBirth`/`localCaseReference` means in-application sorting and forecloses DB-side pagination.
Fine at current scale; it is a deliberate scalability ceiling. Recommend doing it **after** Phase 1
has been running in production, so the key-caching and fail-closed behaviour are proven on the fields
where a mistake is cheapest to unwind.

**Explicitly not encrypting:** `username`, all ids/FKs/status/timestamps, the boolean risk flags, and
`audit_events` — reasons in §2 Tier 3. The audit exclusion is a deliberate safeguarding-assurance
choice, not an oversight, and should be recorded as such.

### Needs a human decision

1. **Phase 2 at all** — accepting in-application sorting and no DB-side pagination for child lists.
2. **The boolean risk flags** — genuinely sensitive, but encrypting them breaks the dashboards. My
   recommendation is to leave them; the human may weigh it differently.
3. **Effort.** Phase 1 is a real piece of work (key cache, fail-closed behaviour, dual-write,
   backfill, verified plaintext retirement) — on the order of the WS-B document work, not an
   afternoon. It should be scheduled against go-live, not slipped in beside it.

### Needs god's adjudication

- Whether Phase 1 lands **before** first apply (encrypting an empty database removes the entire §5
  migration problem and the backup-retention caveat) or after go-live as a migration. **Encrypting
  before there is any real data is dramatically cheaper and I would take it if the schedule allows** —
  it is the single largest cost difference in this document.

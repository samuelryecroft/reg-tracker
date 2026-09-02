# Demo instance

A one-command, fully-populated instance for showing the tracker to clients.

```bash
./scripts/demo-up.sh
```

That starts Postgres, waits for it, and boots the app with the `demo` profile.
Open <http://localhost:8080> and sign in. The first run seeds the data; later
runs reuse it.

To get back to the exact starting state:

```bash
./scripts/demo-up.sh --fresh    # destroys the database, then re-seeds
```

## Logins

Every demo account uses the password **`demo1234`**.

| Username | Role | Sees |
|---|---|---|
| `admin` | Admin | Everything, plus user administration |
| `orgadmin` | Org Admin | All of Beacon's client care providers |
| `coordinator` | Coordinator | Beacon's request queue; allocates and schedules |
| `visitor` | Visitor | Interviews allocated to Naomi Clarke; writes reports |
| `visitor2` | Visitor | Interviews allocated to Ade Balogun |
| `reviewer` | Reviewer | Beacon's submitted reports; approves or rejects |
| `homestaff` | Home Staff | Oakwood House only; raises requests |
| `homestaff2` | Home Staff | Marisco Lodge only |
| `viewer` | Viewer | Read-only, Oakwood House and Marisco Lodge |
| `coordinator.ng` | Coordinator | Northgate's queue — sees **none** of Beacon's data |

`coordinator.ng` is the one to sign in as if a client asks how tenant
separation works: it is a coordinator with the same permissions as
`coordinator`, and it sees a completely different set of records.

## What gets seeded

**Two supplier organisations**, each with their own branding, and the care
providers they serve:

- **Beacon Return Home Services** → Harbourside Children's Care, Willowfield
  Residential Group
- **Northgate Safeguarding Partners** → Stanmore Care Homes

Beneath those: 4 homes, 5 children, 9 demo users covering all seven roles (plus the
platform `admin`), and
**8 interview requests, one in each lifecycle state** — so any screen can be
demonstrated without first driving the workflow to get there:

| State | Child | Shows |
|---|---|---|
| Requested | Alex Brennan | A new request in the coordinator's queue |
| Allocated | Priya Nandra | Visitor named, date not yet agreed |
| Scheduled | Jordan Okafor | Visit booked, report not started |
| Scheduled + draft | Megan Lyall | A report part-written by the visitor |
| Report submitted | Alex Brennan | Awaiting review — the reviewer's screen |
| Report approved | Priya Nandra | Approved, with a generated `.docx` |
| Report rejected | Jordan Okafor | Sent back for amendment, with comments |
| Cancelled | Tomas Vidal | The escape-hatch state |

The approved and rejected reports are put through the **real** review service
rather than written directly, so the approved one has a genuine generated Word
document behind its download button, and the audit trail contains real events
produced by the same code a user's actions would trigger.

### You will also see the application's own baseline data

Every install starts with an organisation called **STEPS with Children** and a
**Default Care Provider** beneath it, created by the database migrations rather
than by this seed. They have no homes or interviews and are left untouched, so
they show up empty alongside the demo tenancy. Sign in as one of the Beacon or
Northgate accounts above and you will not see them.

### Everyone here is invented

No real child, member of staff, home, or organisation is represented. The names,
addresses, case references and contact details are all fictional and fixed, so
the same seed comes out the same way every time.

## Why a profile and not a migration

The seed is a Spring bean gated on the `demo` profile, not a Flyway `Vnn__`
migration. A migration is applied automatically to every database the
application connects to — which would eventually include a real one. The profile
is opt-in, is set nowhere in the committed configuration, and the seeding bean
does not exist at all unless it is active.

The demo profile also lowers two guards that only make sense here: it sets a
well-known admin password (the default profile deliberately has none, and fails
safe without one), and it disables failed-login throttling, because a lockout
triggered by a mistyped password in front of a client is worse than the
protection is worth on invented data. **Never point this profile at a real
database.** The app logs a warning banner at startup when it is active.

## Snapshots

To pin the exact data used in a recorded walkthrough, or to make a demo start
instantly:

```bash
./scripts/demo-dump.sh                  # writes demo-seed.dump
./scripts/demo-restore.sh demo-seed.dump
```

Restoring **drops and recreates** the `return_home_tracker` database, so it asks
for confirmation first.

## Resetting

The seeder skips if the data is already there, rather than wiping and
re-inserting. It cannot reset in place: `audit_events` is append-only at the
database level — a trigger rejects `UPDATE` and `DELETE` — and its rows
reference the seeded users. That is deliberate, and it is the same protection a
real deployment relies on. To start over, throw the database away:
`./scripts/demo-up.sh --fresh`.

# Test contexts — the baseline

**Measured against:** `main` @ `fe2bb3d` (T148), re-measured on T113 Inc 3 and on T168.
**Date:** 2026-09-05.

The test suite builds **8 Spring application contexts** across **37
context-using test classes**, and **7 Hikari pools** against the one shared
Testcontainers Postgres.

**Pools are the number to watch, not contexts.** A context is only expensive
if it builds a `DataSource`; context 4 below builds none. Reading this document
— or `DynamicPropertySourceGuardTest` — as "minimise contexts" will make things
worse, not better.

The budget, all three numbers measured rather than assumed:

| | | |
|---|---|---|
| `max_connections` on `postgres:16-alpine` | **100** | `SHOW max_connections` |
| `superuser_reserved_connections` | **3** | so **97** are usable |
| HikariCP `DEFAULT_POOL_SIZE` | **10** | and `minIdle` defaults to it, so a pool tends toward 10 held connections |

97 usable at 10 per pool is **9 pools**. Today's 7 is up to 70 of 97.

T148 was the suite walking into that ceiling: with the tenth pool live, the
eleventh context failed to start with `FATAL: sorry, too many clients already`
— in whichever class happened to run eleventh, which is why it was twice
written off as environmental flakiness (T93/T120) rather than measured.

`DynamicPropertySourceGuardTest` fences the one mechanism that caused that.
It does not fence context creation in general. This document is the rest of
the answer: what the 6 are, and which of them have to exist.

## The six

| # | What makes it distinct | Classes | Pool | Verdict |
|---|---|---|---|---|
| 1 | `@SpringBootTest`, no `@AutoConfigureMockMvc` | 4 | yes | collapsible, but **don't** — see below |
| 2 | `@SpringBootTest` + `@AutoConfigureMockMvc` | 19 | yes | necessary (the main one) |
| 3 | `@TestPropertySource` enabling Entra/OAuth2 login | 3 | yes | necessary |
| 4 | `@WebMvcTest` slice (different bootstrapper) | 1 | **no** | necessary, and the cheapest |
| 5 | `@TestPropertySource` enabling login throttling | 1 | yes | necessary |
| 6 | `webEnvironment = RANDOM_PORT` (Playwright) | 7 | yes | necessary |
| 7 | `@TestPropertySource` opening the break-glass path | 2 | yes | **chosen** — see below |
| 8 | unprovisioned-KEK `KeyProvider` + auto-create off | 1 | yes | **chosen** — see below |

Context 4 is why there are 8 contexts but only 7 pools: a `@WebMvcTest` slice
builds no `DataSource`.

### Context 3 — necessary, and now shared rather than repeated

Originally one class. T113 added logout and break-glass tests that also need
the flag on, and each would have forked its own context: the first took the
suite to **6 pools**, measured by the highest `HikariPool-N` in a full run.

They now share `AbstractEntraEnabledTest`, which carries the property set and
the stub registration, and the suite is back to **5**. The sharing works for the
mirror of T148's reason rather than despite it: `@DynamicPropertySource` forks
per registrar *method* even with identical values, while `@TestPropertySource`
keys on the merged property *values*, so an identical set genuinely does share
one context. A subclass that adds its own `@TestPropertySource` forks again.

### Contexts 3 and 5 — necessary

`EntraLoginEnabledTest` turns OAuth2 login on; `LoginThrottlingIntegrationTest`
turns throttling on with a 3-attempt limit. Both use `@TestPropertySource`, and
both are testing a configuration that **must not leak into the other 30
classes** — a global 3-attempt lockout would break unrelated login tests. The
separate context is the isolation, not an accident. Leave them.

### Context 7 — chosen, not arrived at

T113 Inc 4's break-glass gate is startup-bound, so a test that needs the
emergency path open must override the property, and that forks a context.

**A per-request read would have avoided this pool, and was rejected.** It would
have bought "close the path without a restart" — real, but not in this
deployment: no `@RefreshScope`, no Spring Cloud, actuator exposing only
`health` and `info`, and the value arriving as an App Service app setting whose
change restarts the app anyway. So the flag is startup-bound in production
however the read is written, and the per-request version would have been
mutable only from a test.

The principle, which outlives the decision: **a test-infrastructure budget must
not shape a security control's runtime semantics.** This document exists to stop
the suite reaching a pool it did not decide to spend, not to stop it spending
one on purpose. Measured at 5 before and 6 after.

**The stop-and-ask line has moved once, deliberately.** It said 7 when this was
written, and T168 spent the 7th with god's sign-off (context 8 below). It is
**8** now. Moving it is allowed; moving it without measuring, or without saying
who agreed, is not — that is how a budget becomes a number nobody believes.

`AbstractBreakGlassEnabledTest` holds the override so that however many
enabled-path tests there end up being, they cost one pool between them. The
closed-path tests need no override and stay in context 2 — the default is
closed, which is the configuration they describe.

### Context 8 — chosen, and the reason is worth more than the pool

`UnprovisionedKekIntegrationTest` (T168) supplies a `@Primary` `KeyProvider`
that fails every operation, plus `auto-create-keys=false`, standing in for an
organisation whose Key Vault KEK was never provisioned. A nested
`@TestConfiguration` is a context customizer, so this forks — knowingly, and
signed off before it was spent.

**One class, both halves.** The admin being warned at onboarding and the write
later failing well are one story and need the same two overrides, so they share
this context rather than costing a second. That is the `AbstractEntraEnabledTest`
lesson applied before the fact instead of after it.

It could not be avoided by sharing: every other integration test needs a
*working* key provider, so this context is the one thing it is for. And it could
not be replaced by a cheaper test, which is the actual argument. There was
already a unit test calling the exception handler directly, and it proved the
mapping. What no unit test could reach is whether a `FieldCryptoException` raised
inside a Hibernate `PreInsertEventListener` — during flush, not in the controller
body — survives Hibernate, the transaction interceptor and Spring's handler
resolution to arrive at that handler at all. **The half that broke in production
was routing, and routing is the half a direct call cannot exercise.**

Writing it is what disproved the ticket's premise: the pre-fix path did not
return the assumed 500. Spring's `@ExceptionHandler` resolution walks the *cause*
chain, so `handleDocumentSecurity` already matched `KeyUnavailableException` and
already answered 503 — with a message about a *report* that could not be opened,
to someone adding a *child*. That is the sort of thing a pool buys.

### Context 6 — necessary

The Playwright suite needs a real servlet container on a real port
(`server.port=0`), so it cannot share a `MOCK` environment context whatever
else is done. It also carries `AbstractUiTest`'s registrar for the admin seed
credentials, which is the second allowed entry in
`DynamicPropertySourceGuardTest`. Seven classes share this one context — that
consolidation was already done (T128); a naive version of the WebKit smoke test
had its own duplicate context and hit the connection ceiling twice while it was
being written.

### Context 4 — necessary, and worth keeping *because* it is a slice

`SecurityConfigTest` is `@WebMvcTest(controllers = ...)`. A slice is a
different bootstrapper, so it can never share with a `@SpringBootTest`. It is
also the only context that costs no database connection. Converting it to
`@SpringBootTest` to save a context would *add* a pool. Leave it.

### Contexts 1 and 2 — the only collapsible pair, and it is all-or-nothing

These two are identical in every cache-key field except one context customizer:
`@AutoConfigureMockMvc`'s `ImportsContextCustomizer`. Context 1 is a strict
subset of context 2.

The four classes in context 1 — `DatabaseResetIntegrationTest`,
`ReturnHomeTrackerApplicationTests`, `FieldEncryptionIntegrationTest`,
`InterviewRequestRepositoryTest` — are `@SpringBootTest` without
`@AutoConfigureMockMvc`. Adding that annotation to all four would merge them
into context 2 and return one context and one pool (10 connections).

**Recommendation: don't.** Not as a weighing of costs — the choice is
structurally narrower than it looks.

A context exists as long as *any* class needs it. So moving three of the four
saves nothing at all: group 1 survives for the fourth, and the count stays
where it is. The collapse is all-or-nothing, and the "all" necessarily
includes `ReturnHomeTrackerApplicationTests`, whose entire value is proving that
a context *close to the production one* loads.

So the question is not "are 10 connections worth one test's fidelity". It is
"is a production-fidelity context-load test worth one context and one pool",
and that answer is plainly yes. There is no partial version of this trade to
tune.

## How to re-measure

Two independent methods that agreed exactly, which is why the number above is
trustworthy rather than merely plausible.

**At runtime, no configuration needed.** A *new* context logs
`Started <TestClass> in Ns`; a cached one logs nothing. So:

```bash
./mvnw verify | grep -o "Started [A-Za-z]* in [0-9.]* seconds"    # one line per context
./mvnw verify | grep -o "HikariPool-[0-9]* - Starting" | sort -u  # one line per pool
```

A class whose context *fails to load* is absent from that list — which is how
T148's eleventh class was identified.

**Offline, without starting anything.** Spring caches on
`MergedContextConfiguration`, whose `equals` compares nine fields and
deliberately *not* the test class. Grouping every test class by that object
reproduces the cache exactly:

```java
BootstrapUtils.resolveTestContextBootstrapper(testClass).buildMergedContextConfiguration()
```

Group by the result. It runs in about two seconds, starts no container, and
tells you *why* two classes differ rather than only that they do.

## When this becomes a ticket

A baseline is only useful if it arms a decision, so here is the number rather
than a description of the state:

| Pools | What to do |
|---|---|
| 5 (today) | nothing |
| **7** | open a ticket — a third of the margin is gone and two more slips reproduce T148 |
| **9** | stop: the next context to appear will fail, in whichever class happens to run last |

**Reach for the ceiling before reaching for the test design.** The 100 is
Postgres' default, not a law, and the container can be told otherwise in one
line:

```java
new PostgreSQLContainer<>("postgres:16-alpine")
        .withCommand("postgres", "-c", "max_connections=200")
```

That is not free — each backend costs memory on the runner — but it is the
right *first* lever precisely because it costs nothing in test design. Anyone
hitting this wall under time pressure should raise the ceiling rather than
start merging contexts that exist for good reasons; the contexts above are
isolation, and trading isolation for connections is the wrong direction.

## What would add a seventh

A guard covers only the first of these:

- a `@DynamicPropertySource` on a class other than the two allowed base classes
  — **fenced** by `DynamicPropertySourceGuardTest`;
- a `@TestPropertySource` or `@ActiveProfiles` on a new class;
- a `@MockitoBean`/`@MockBean` (each distinct set of mocked beans is its own
  cache key);
- a different `webEnvironment`;
- a new `@AutoConfigure...` annotation on some but not all classes — the
  contexts 1 vs 2 split above is exactly this shape.

None of these is wrong to do. Each costs a context and roughly 10 connections,
and the cost is invisible at the point where it is paid — that is the whole
lesson of T148.

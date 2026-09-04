# Test contexts — the baseline

**Measured against:** `main` @ `fe2bb3d` (T148), re-measured on T113 Inc 3.
**Date:** 2026-09-04.

The test suite builds **6 Spring application contexts** across **33
context-using test classes**, and **5 Hikari pools** against the one shared
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

97 usable at 10 per pool is **9 pools**. Today's 5 is up to 50 of 97.

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

Context 4 is why there are 6 contexts but only 5 pools: a `@WebMvcTest` slice
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
saves nothing at all: group 1 survives for the fourth, and the count stays at 6
contexts and 5 pools. The collapse is all-or-nothing, and the "all" necessarily
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

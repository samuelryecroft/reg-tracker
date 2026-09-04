# Test contexts — the baseline

**Measured against:** `main` @ `fe2bb3d` (T148). **Date:** 2026-09-04.

The test suite builds **6 Spring application contexts** across **33
context-using test classes**, and **5 Hikari pools** against the one shared
Testcontainers Postgres.

That is the number to watch. At Hikari's default pool size, 5 pools is 50
connections against Postgres' default ceiling of 100. T148 was the suite
walking into that ceiling: 10 pools exhausted it, and the 11th context died
with `FATAL: sorry, too many clients already` — in whichever class happened to
run eleventh, which is why it was twice written off as environmental flakiness
(T93/T120) rather than measured.

`DynamicPropertySourceGuardTest` fences the one mechanism that caused that.
It does not fence context creation in general. This document is the rest of
the answer: what the 6 are, and which of them have to exist.

## The six

| # | What makes it distinct | Classes | Pool | Verdict |
|---|---|---|---|---|
| 1 | `@SpringBootTest`, no `@AutoConfigureMockMvc` | 4 | yes | **collapsible** — see below |
| 2 | `@SpringBootTest` + `@AutoConfigureMockMvc` | 19 | yes | necessary (the main one) |
| 3 | `@TestPropertySource` enabling Entra/OAuth2 login | 1 | yes | necessary |
| 4 | `@WebMvcTest` slice (different bootstrapper) | 1 | **no** | necessary, and the cheapest |
| 5 | `@TestPropertySource` enabling login throttling | 1 | yes | necessary |
| 6 | `webEnvironment = RANDOM_PORT` (Playwright) | 7 | yes | necessary |

Context 4 is why there are 6 contexts but only 5 pools: a `@WebMvcTest` slice
builds no `DataSource`.

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

### Contexts 1 and 2 — the only collapsible pair, and probably not worth it

These two are identical in every cache-key field except one context customizer:
`@AutoConfigureMockMvc`'s `ImportsContextCustomizer`. Context 1 is a strict
subset of context 2.

The four classes in context 1 — `DatabaseResetIntegrationTest`,
`ReturnHomeTrackerApplicationTests`, `FieldEncryptionIntegrationTest`,
`InterviewRequestRepositoryTest` — are `@SpringBootTest` without
`@AutoConfigureMockMvc`. Adding that annotation to all four would merge them
into context 2 and return one context and one pool (10 connections).

**Recommendation: don't, for now.** It buys 10 connections against 50 already
free, and it costs something real: `ReturnHomeTrackerApplicationTests` exists
to prove the application context loads, and the closer that context is to the
production one, the more the test is worth. Adding MockMvc autoconfiguration to
it to save a pool makes the smoke test slightly less honest about what it
proves. The other three simply do not use MockMvc.

Worth revisiting only if the pool count climbs — at which point this is the
first place to look, which is the point of writing it down.

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

# AvilixLogger 2.1.16 — remaining tick-wrapper overhead

Based on 2.1.15, retaining earlier fixes.

- `CauseContext.unattributed()` returns a permanent inert handle when the current thread's cause stack is empty. Ordinary simulation ticks allocate no scope. The inert handle holds neither a thread nor an actor and never changes state. If any outer scope exists, normal isolation remains; repeated or stale closes cannot pop another action.
- Simulation wrappers use a server-thread authorization lease keyed by server identity, tick number and invalidation epoch, lasting at most 50 ms from the start of the API check. A long tick must refresh. Other threads always call the API directly.
- Actual event/publication gates retain direct `LoggerConfig.isEnabled()` / AuthCore checks. Denial, API errors, attach and shutdown invalidate the simulation lease. No permanent authorization cache or authorization override was added.
- Both positive and negative simulation results can be reused until tick/lease expiry. A grant not otherwise observed becomes visible at the next refresh. A revocation not observed through a direct check can leave capture enabled only until that refresh; publication still uses the direct check.

Validation:
- 15 standalone context checks: allocation-free empty path; player/simulation nesting; exceptions; double, stale, out-of-order and foreign-thread close; deep stack and shutdown cleanup.
- 43 native tick-wrapper checks with an isolated test API, including repeated wrapper calls, direct denial invalidation, 50 ms expiry, tick-number expiry, off-thread checks, API failure and shutdown. Passed against the obfuscated release too. Test API is absent from the delivered JAR.
- Context-only microbenchmark: 0 bytes/op after warmup; median 3.12 ns/op over five rounds of 2M operations. These numbers do not measure the whole tick wrapper, real AuthCore or production MSPT. Prior 2.1.15 measurement was 24 bytes/op and 14.56 ns/op.

Real AuthCore internals and the production server's Spark profile remain unavailable. The bounded lease is a local integration policy, not a claim of immediate external revocation notification.

Install by replacing the old Logger JAR while the server is stopped; configuration changes are not required.

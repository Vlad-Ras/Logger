# AvilixLogger 2.1.15 — tick wrapper performance hotfix

Based on released 2.1.14, including attribution, optimization-integrity and item-serialization fixes.

Changes:
- Keep the empty CauseContext deque between ticks; ordinary close uses peek/pop, with removeFirstOccurrence retained for out-of-order closes. Closed handles release their cause. Stacks deeper than 64 are discarded after unwinding. Clear both actor and source ThreadLocals on server shutdown.
- Clear Create automation buffers once in finish/finally, skip empty collection clears and release retained source/entity references. Existing per-operation size limits remain.
- Reuse the authorization decision already made by the synchronous block ticker when starting Create capture. Idle completion does not query authorization again. Real publication still checks authorization; no cross-tick permission cache is introduced.
- Check the local enabled flag before calling AuthCore.
- Keep automatic transfer publication inside the unattributed actor scope; restore source context even if capture initialization or finalization throws.
- Prevent reentrant ticks from resetting the reusable capture during publication.

Validation:
- 13 standalone CauseContext checks: deque reuse, nested player/simulation causes, exception unwinding, duplicate/out-of-order/wrong-thread close, reference cleanup, deep-stack and lifecycle cleanup.
- 32 native tick-wrapper checks, also run against the obfuscated release: real BoundTickingBlockEntity invocation, capture/ticker/finalizer exceptions, context restoration, one AuthCore call on an idle Create tick, nested capture protection, buffer cleanup, authorization revocation.
- Existing native Create regression passed, including 41 automation scenarios for PSI items/fluids, both double-chest halves, partial capacity, cancellation, conflicts and rollback locks. These use detached capture and do not exercise a real AuthCore service or ClickHouse server.
- Test AuthCore API is isolated in src/performanceSmoke, defaults to deny, and is absent from the release archive. Main code continues to deny authorization when real AuthCore is absent.

Comparative microbenchmark (same Java 21.0.2 process configuration, 1M warmup and five rounds of 2M operations; old/new run in separate processes):
- Previous context: 168 bytes/op; median 41.39 ns/op.
- New context: 24 bytes/op; median 14.56 ns/op.
- Measures unattributed context enter/peek/close only. Not a production tick benchmark or an expected 7.2 ms MSPT saving. Confirm average/p95 MSPT and wrapper self time with comparable Spark captures on the actual server.

Install by stopping the server and replacing the old Logger JAR. No configuration changes required.

# AvilixLogger 2.1.13 — preserve event information during optimization

Based on 2.1.12 (`1c73f61`), including its actor-attribution fixes. This release remains below 2.2.0 and does not contain the later unfinished Create branch work.

## Confirmed defects and changes

- Drop aggregation previously keyed only dimension, actor UUID and normalized item NBT. Drops before/after a teleport therefore merged, with the sum assigned to the last coordinates and timestamp. The key now includes coordinates, action, actor name, source and extra. Bursts are bounded to 250 ms instead of extending indefinitely; their first capture timestamp is preserved.
- Generic duplicate filtering ignored counts, block/entity after states and portions of the payload; sampled hashes could treat distinct long NBT as identical. Fingerprints now compare complete payloads and provenance after background materialization. Separate container clicks are never suppressed solely by a short time window. The exact-payload cache has an 8 MiB estimated budget; expiration/reset only allows additional rows.
- ClickHouse batch coalescing summed separate container deltas, kept the earliest timestamp and ignored source. It now preserves every captured row. Existing HTTP batching, durable delivery and background serialization remain in place.

No evidence from these tests establishes that every old server anomaly had the same cause. In particular, spatial drop merging does not explain an aircraft assigned to an absent player; the unsupported actor inference was fixed in 2.1.12.

## Validation

- Full main-source compilation with Java 21 against NeoForge 21.1.219/Minecraft 1.21.1 and actual optional mod dependencies.
- New deterministic regressions cover teleport-separated drops, a sustained drop stream, different item counts, after states, long-NBT changes between former sample positions, differing sources, repeat-click suppression and distinct SQL batch identities/timestamps. The teleport test failed on 2.1.12 and passes on 2.1.13.
- AsyncPipelineTest passes, including the new regressions, 20,000-row concurrent saturation/drain, queued actor/coordinate/dimension isolation while four producers mutate their original rows, journal replay and local HTTP/storage fixtures.
- ProGuard 7.4 release built successfully; release enum/record/public serialization fields checked. Final JAR contains no smoke/test code or embedded Minecraft/mod dependencies.
- Native fixture against actual obfuscated release bytes with Create 6.0.11-313 and Immersive Aircraft 1.4.6+1.21.1 passes 27 checks; the release also passes 16 checks without Create/Immersive Aircraft. No logger mixin application failure.
- Test stand lacks AuthCore and production logging correctly remains denied. Native actor/scope checks and local HTTP fixtures do not establish authorized end-to-end logging on the user's live server. Full modpack/client GUI and live database were not tested.

## Deployment and limits

Stop server, replace the prior logger with one `avilixlogger-2.1.13.jar`, restart; update client copies with the GUI. Keep configuration, AuthCore and database. Validate new events after restart. Existing incorrect rows remain unchanged.

Fingerprint comparisons can retain more genuine rows than the previous aggressive filter, increasing stored row count. Expensive encoding/comparison stays on workers; no server TPS benchmark was performed.

SHA-256: `0e20b067a794b72e8de4c94af856bbd82d2dffbae9aca26f4cb4f7c9dfb62aef`.

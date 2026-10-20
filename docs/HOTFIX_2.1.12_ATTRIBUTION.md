# AvilixLogger 2.1.12 — evidence-based actor attribution

Emergency branch `fix/actor-attribution-2.1.12`, based on recovered 2.1.11 sources `cd8f59b`. The ongoing 2.2.0 branch and its later Create work are separate.

The generic logger must never identify a human solely from position, a recent unrelated action, an ownership NBT tag, a previous mount, or an earlier hit. Those historical facts do not prove who caused a new mutation.

## Changes

- Disabled proximity/recent-action resolution, including Create/Aeronautics and delayed aircraft-placement fallbacks. Old inferred owner tags are no longer used to identify the author of an entity spawn/removal.
- Generic removal attribution uses the current synchronous action and cannot be overridden by the last visitor. VehicleEntity captures the player in the actual DamageSource for that hurt invocation; environmental damage shields the outer cause. The persistent lastPlayer reference and duplicate removal emitter were removed.
- Added exception-safe wrappers for game-mode use/break, commands, Player.attack/interactOn, interaction packets and aircraft placement. Scope close removes its own token, works with nesting/out-of-order close and never pops someone else's cause. FakePlayer does not become a human actor. Entity/block/fluid simulation masks outer causes.
- Create owner metadata remains metadata; cart events/world effects no longer charge the historical owner as actor.
- Delayed block scans and open-to-close container observations are recorded without a human actor. Generic container clicks capture before/after within the actual packet call and advance observer baselines; pre-click/close residual changes remain unattributed. AE2 and mounted Create transfer auditing keep their dedicated boundaries.
- Preserved runtime parameter/type annotations in ProGuard, including Mixin @Coerce. Shipping them is necessary for Create redirects to remain valid after obfuscation.
- Existing storage rows are not changed. No database migration can infer the real actor from an already incorrect row.

## Validation

Java 21.0.2, Minecraft 1.21.1, NeoForge 21.1.219; Create 6.0.11-313 and Immersive Aircraft 1.4.6+1.21.1.

- All main Java sources compiled from this branch against the actual external binaries; test source set compiled separately.
- AsyncPipelineTest passed: 20,000-row saturation/handoff, ordering, snapshots, shutdown, durable replay and fixture HTTP/storage contracts.
- Native actor fixture passed 27 checks both from source classes and from the actual obfuscated release bytes. It tests an absent owner/old visitor versus a real actor, scope nesting/isolation/close/exception, FakePlayer, rejected guessing and a real transformed gameMode.useItem throwing from a fixture item. Actual IA quadrocopters demonstrate that environmental destruction after an earlier player hit has no actor, direct player destruction keeps the correct actor, and environmental damage does not inherit an unrelated outer command actor.
- The release fixture also passed 16 checks without Create/Ponder/Flywheel/Immersive Aircraft. Optional missing-target warnings are present; no logger mixin application failures remain in the final release run with Create/IA.
- The native fixture is test-only and does not replace or bypass AuthCore. The stand has no AuthCore: production logging correctly remains denied. The fixture validates transformed calls, actor resolution and scope behavior, not a successful authorized ClickHouse emission on the user's server.
- Production JAR has expanded version 2.1.12 metadata; contains no smoke fixtures, test items, authorization stubs or embedded Minecraft/Create/AE2 dependencies. Core implementation and AuthCore bridge are obfuscated. Runtime-sensitive enum/record/LogEntry metadata checked separately.
- The user's running server, full modpack, authorized ClickHouse connection and rendered client GUI have not been tested in this task.

## Installation

Stop the server, replace the old logger JAR with `avilixlogger-2.1.12.jar`, keep a single logger JAR in `mods`, restart. Preserve existing configuration, AuthCore and database. Update the client copy too where the logger GUI is installed.

SHA-256: `335f9fa243611e9993250e0fcae238f32f1e3eb051bee484774ca4ed08ea612b`.

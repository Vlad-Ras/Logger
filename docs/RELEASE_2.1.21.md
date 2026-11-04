# 2.1.21 — contraption state recovery and translated GUI search

Based on the verified 2.1.20 release; no development 2.2.0 action rollback changes are imported.

## Whole-construction recovery

`/log cart recover <UUID> latest` now reconstructs the last captured state, using the newest full checkpoint followed by recorded cargo, fluid, same-type block state/NBT and disabled-actor changes. `/log cart recover <UUID> <entry>` stops at that exact row, including rows without a full snapshot. The GUI enables recovery from any selected row. Capture IDs govern ordering, even across clock changes. Before removal/disassembly is used when after no longer contains a whole construction.

Preparation is bounded to 100000 rows/128 MiB and runs on the existing single worker with a bounded queue. It never reads or writes the affected world, recipients or external inventories. No full NBT polling or inventory scans are added to ticks. Existing confirmation, authorization, duplicate checks and disk reservation remain in place.

Item/fluid before values must match the reconstructed snapshot. Missing checkpoints, unknown/partial codecs, unsupported storage changes, unknown item components, duplicate block/storage addresses and over-capacity fluids refuse recovery with the affected entry. The preview identifies the target entry, base checkpoint, applied changes and totals of mounted storages/items/fluid.

All mounted storages undergo strict decode/encode/decode validation before grant, including every item component and fluid capacity. Unknown fields that the current codec would lose cause refusal. Original raw block/actor/settings fields remain untouched except the recorded changes being replayed.

## GUI search

A client-side registry name index maps translated block/item/entity names to registry IDs. Search `Незерак` includes `minecraft:netherrack`, including the dedicated block field. The active language/resources are respected. Text and translated alternatives form a single OR group inside every database query before LIMIT, including feed, split-table fallback and compressed legacy compatibility. Other time/radius/player filters remain combined with AND. Pages are never locally thinned. More than 256 matching IDs requires a narrower term; results are never silently truncated. Network protocol 6 requires updating server and client together.

## Validation

Passed Java 21 compilation, obfuscation, mixin safety, async/data regressions and native Create server tests: https://github.com/Vlad-Ras/Logger/actions/runs/39163571628 . Runtime commit: `ea09252e664ca93dd248f1189f002c709fd3df13`; source tree exactly matches local `dc71e83e0c62d0455b114ef4e9431a37e3f81407`.

Tests cover selected/latest/cursor recovery, same-type block settings, item components, fluid replacement, controls, corrupted chains/codecs/capacity, Russian name lookup and SQL pushdown. Existing rollback commit/cancel, recipient, automation, native menu and loot tests also pass. Detached replay restores its thread scope. No smoke classes are packaged in the delivered JAR.

Delivered `avilixlogger-2.1.21.jar`: 713859 bytes; SHA-256 `93cd66557c32e7aa36f0f54953bd1b81e34412cfc3dafdf51233c16bbc7fb40d`. Downloaded Actions artifact digest and ZIP/JAR CRC verified; embedded mod version is 2.1.21. GUI rendering and the full authorized production AuthCore/DB/admin flow have not yet been exercised in a Minecraft client.

## Explicit boundaries

This reconstructs recorded state, not unrecorded changes at the instant of a crash. Couplings, nested contraptions, base chest-minecart cargo, legacy mounted-storage format and mechanism type replacement still require additional support and are refused. Only standard SimpleMountedStorage descendants and native FluidTankMountedStorage support delta replay; complete snapshots can use other registered storage codecs if strict lossless validation passes. No actions performed in the world are undone by item recovery. Offline inventories and arbitrary unloaded storage are not exhaustively searched for the original.

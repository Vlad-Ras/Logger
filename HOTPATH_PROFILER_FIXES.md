# Hot-path profiler fixes

Applied to the full Logger source archive.

## Fixed profiler hotspots

1. `NbtSerde.invokeBlockEntitySave(ServerLevel, BlockEntity)`
   - `writeBlockEntity` now first uses direct 1.21.x API path: `be.saveWithFullMetadata(level.registryAccess())`.
   - Reflection chain remains only as compatibility fallback.

2. `LoggerEventHandlers.onItemPickup(ItemEntityPickupEvent.Pre)`
   - Added `NbtSerde.writeItemStackHotPath(...)`.
   - Plain vanilla pickup/drop stacks are serialized as tiny SNBT: `{id:"minecraft:stone",count:64}`.
   - Full `ItemStack.save(...)` is still used for modded items, damaged tools, custom data, enchantments, containers, BE/entity data, etc.

3. `PreparedStatement.prepareStatement(...)` in MySQL fallback
   - Disabled server-side prepared statements for the fallback writer.
   - Kept batch rewrite and added session/config caching flags to reduce driver overhead.

## Build note

Run locally with:

```bat
gradlew --stop
gradlew clean build --no-configuration-cache --refresh-dependencies
```

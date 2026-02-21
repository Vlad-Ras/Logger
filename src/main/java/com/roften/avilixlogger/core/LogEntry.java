package com.roften.avilixlogger.core;

import java.util.UUID;

/**
 * One log line (JSON) stored in files.
 *
 * Design goals:
 * - forward-compatible: unknown fields should be ignored
 * - rollback-friendly: contains "before" snapshots where possible
 */
public final class LogEntry {
    /** SQL row id (filled on reads). */
    public long id;
    public long ts;                // epoch millis
    public String dim;             // dimension id, e.g. "minecraft:overworld"
    public ActionType type;

    public UUID actorUuid;         // may be null (e.g. explosions)
    public String actorName;       // best-effort

    public String source;          // optional attribution hint, e.g. "create:schematicannon@x,y,z"

    public int x;
    public int y;
    public int z;

    // block snapshots (SNBT)
    public String blockBefore;     // SNBT of NbtUtils.writeBlockState
    public String beBefore;        // SNBT CompoundTag of block entity before
    public String blockAfter;      // SNBT
    public String beAfter;         // SNBT

    // entity snapshots
    public String entityType;      // namespaced id
    public UUID entityUuid;
    public String entityNbt;       // SNBT CompoundTag

    // item snapshots (best-effort)
    /** Single stack snapshot used by simple events (pickup/drop/craft/smelt). */
    public String itemStackNbt;    // SNBT (ItemStack.save)
    public int count;

    /** Optional "before" and "after" snapshots for player inventory rollback (SNBT CompoundTag). */
    public String playerInvBefore;
    public String playerInvAfter;

    /**
     * Optional strict container slot snapshots (SNBT CompoundTag).
     *
     * Format: {Size:int, Items:[{Slot:byte, ...ItemStack...}, ...]}
     *
     * This is intentionally independent from block-entity NBT, because many modded storages
     * (e.g. Create) do not reliably expose their contents via BE NBT diffs.
     */
    public String containerSlotsBefore;
    public String containerSlotsAfter;

    /** Free-form extra context (JSON string). */
    public String extra;
}

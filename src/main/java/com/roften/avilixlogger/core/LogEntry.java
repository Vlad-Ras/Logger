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
     * Format: {Size:int, Items:[{Slot:int, ...ItemStack...}, ...]}
     *
     * This is intentionally independent from block-entity NBT, because many modded storages
     * (e.g. Create) do not reliably expose their contents via BE NBT diffs.
     */
    public String containerSlotsBefore;
    public String containerSlotsAfter;

    /** Free-form extra context (JSON string). */
    public String extra;

    public enum SnapshotField { BE_BEFORE, BE_AFTER, ITEM, ENTITY, SLOTS_BEFORE, SLOTS_AFTER }
    private transient java.util.EnumMap<SnapshotField, net.minecraft.nbt.Tag> snapshots;
    private transient long snapshotBytes;

    /** The supplied detached tag transfers ownership to this row; never pass live mod NBT. */
    public void deferSnapshot(SnapshotField field, net.minecraft.nbt.Tag tag) {
        if (tag == null) return;
        if (snapshots == null) snapshots = new java.util.EnumMap<>(SnapshotField.class);
        net.minecraft.nbt.Tag previous = snapshots.put(field, tag);
        if (previous != null) snapshotBytes -= previous.sizeInBytes();
        snapshotBytes += tag.sizeInBytes();
    }

    long snapshotBytes() { return snapshotBytes; }

    public void materializeSnapshots() {
        if (snapshots == null) return;
        snapshots.forEach((field, tag) -> {
            String snbt = tag.toString();
            switch (field) {
                case BE_BEFORE -> beBefore = snbt;
                case BE_AFTER -> beAfter = snbt;
                case ITEM -> itemStackNbt = snbt;
                case ENTITY -> entityNbt = snbt;
                case SLOTS_BEFORE -> containerSlotsBefore = snbt;
                case SLOTS_AFTER -> containerSlotsAfter = snbt;
            }
        });
        snapshots = null;
        snapshotBytes = 0;
    }

    public LogEntry copyForQueue() {
        LogEntry copy = new LogEntry();
        copy.id = id;
        copy.ts = ts;
        copy.dim = dim;
        copy.type = type;
        copy.actorUuid = actorUuid;
        copy.actorName = actorName;
        copy.source = source;
        copy.x = x;
        copy.y = y;
        copy.z = z;
        copy.blockBefore = blockBefore;
        copy.beBefore = beBefore;
        copy.blockAfter = blockAfter;
        copy.beAfter = beAfter;
        copy.entityType = entityType;
        copy.entityUuid = entityUuid;
        copy.entityNbt = entityNbt;
        copy.itemStackNbt = itemStackNbt;
        copy.count = count;
        copy.playerInvBefore = playerInvBefore;
        copy.playerInvAfter = playerInvAfter;
        copy.containerSlotsBefore = containerSlotsBefore;
        copy.containerSlotsAfter = containerSlotsAfter;
        copy.extra = extra;
        if (snapshots != null) copy.snapshots = new java.util.EnumMap<>(snapshots);
        copy.snapshotBytes = snapshotBytes;
        return copy;
    }
}

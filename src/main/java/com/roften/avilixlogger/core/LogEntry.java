package com.roften.avilixlogger.core;

import java.util.UUID;

/**
 * One log line (JSON) stored in files.
 *
 * Design goals:
 * - forward-compatible: unknown fields should be ignored
 * - rollback-friendly: contains "before" snapshots where possible
 */
public final class LogEntry implements QueuedLogEvent {
    @Override public LogEntry resolve() {
        if (rejectedSnapshotDelta) return null;
        if (snapshotDelta == null) return this;
        var before = snapshots == null ? null : snapshots[SnapshotField.BE_BEFORE.ordinal()];
        var after = snapshots == null ? null : snapshots[SnapshotField.BE_AFTER.ordinal()];
        boolean changed = snapshotDelta.changed(before instanceof net.minecraft.nbt.CompoundTag tag ? tag : null,
                after instanceof net.minecraft.nbt.CompoundTag tag ? tag : null);
        snapshotDelta = null;
        if (changed) return this;
        rejectedSnapshotDelta = true;
        snapshots = null;
        snapshotBytes = 0;
        deferredBlockBefore = null;
        deferredBlockAfter = null;
        return null;
    }
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
    public String entityName;      // target name captured at the event, independent of the actor
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

    private transient boolean rollbackParsed;
    private transient net.minecraft.nbt.CompoundTag rollbackEntity, rollbackItem;

    long prepareForRollback() {
        if (rollbackParsed) return 0;
        rollbackParsed = true;
        if (type == ActionType.ENTITY_DEATH || type == ActionType.PLANE_REMOVE) rollbackEntity = NbtSerde.fromSnbt(entityNbt);
        if (type == ActionType.ITEM_DROP || type == ActionType.ITEM_PICKUP || type == ActionType.PLANE_PICKUP
                || type == ActionType.ITEM_CRAFT || type == ActionType.ITEM_SMELT) rollbackItem = NbtSerde.fromSnbt(itemStackNbt);
        return PayloadSizeEstimator.estimateTags(rollbackEntity, rollbackItem);
    }
    net.minecraft.nbt.CompoundTag rollbackEntity() { prepareForRollback(); return rollbackEntity; }
    net.minecraft.nbt.CompoundTag rollbackItem() { prepareForRollback(); return rollbackItem; }

    public enum SnapshotField { BE_BEFORE, BE_AFTER, ITEM, ENTITY, SLOTS_BEFORE, SLOTS_AFTER, PLAYER_INV_BEFORE, PLAYER_INV_AFTER }
    private static final SnapshotField[] SNAPSHOT_FIELDS = SnapshotField.values();
    private transient net.minecraft.nbt.Tag[] snapshots;
    private transient long snapshotBytes;
    transient boolean aggregateDrop;
    private transient net.minecraft.world.level.block.state.BlockState deferredBlockBefore;
    private transient net.minecraft.world.level.block.state.BlockState deferredBlockAfter;
    private transient SnapshotDeltaPolicy snapshotDelta;
    private transient boolean rejectedSnapshotDelta;

    /** Only detached NBT is inspected by resolve(); no callback or live-world reference. */
    public void deferSnapshotDelta(SnapshotDeltaPolicy policy) {
        if (policy == null || type != ActionType.BLOCK_ENTITY_NBT_CHANGE || rejectedSnapshotDelta)
            throw new IllegalArgumentException("Snapshot delta requires unresolved block-entity evidence");
        snapshotDelta = policy;
    }

    public void deferBlockBefore(net.minecraft.world.level.block.state.BlockState state) { deferredBlockBefore = state; }
    public void deferBlockAfter(net.minecraft.world.level.block.state.BlockState state) { deferredBlockAfter = state; }

    /** The supplied detached tag transfers ownership to this row; never pass live mod NBT. */
    public void deferSnapshot(SnapshotField field, net.minecraft.nbt.Tag tag) {
        if (tag == null) return;
        if (snapshots == null) snapshots = new net.minecraft.nbt.Tag[SNAPSHOT_FIELDS.length];
        int index = field.ordinal();
        net.minecraft.nbt.Tag previous = snapshots[index];
        if (previous == tag) return;
        snapshots[index] = tag;
        if (previous != null) snapshotBytes -= previous.sizeInBytes();
        snapshotBytes += tag.sizeInBytes();
    }

    /** Copy an unpublished detached payload without serializing it on the server thread. */
    public net.minecraft.nbt.Tag copyDeferredSnapshot(SnapshotField field) {
        var tag = snapshots == null ? null : snapshots[field.ordinal()];
        return tag == null ? null : tag.copy();
    }

    long snapshotBytes() { return snapshotBytes; }

    public void attachCartItemIdentity() {
        if (CreateContraptionKind.id(source) != null) return;
        if (snapshots == null || !(snapshots[SnapshotField.ITEM.ordinal()] instanceof net.minecraft.nbt.CompoundTag item)) return;
        net.minecraft.nbt.CompoundTag audit = item.getCompound("components").getCompound("create:minecart_contraption_data").getCompound("AvilixCartAudit");
        if (audit.hasUUID("Id")) { source = CartAuditContext.prefix(audit.getUUID("Id")) + "item:0"; LogIdGenerator.ensure(this); }
    }

    public void materializeSnapshots() {
        if (snapshotDelta != null) throw new IllegalStateException("Resolve snapshot delta before encoding");
        if (rejectedSnapshotDelta) return;
        if (deferredBlockBefore != null) blockBefore = NbtSerde.writeBlockState(deferredBlockBefore);
        if (deferredBlockAfter != null) blockAfter = NbtSerde.writeBlockState(deferredBlockAfter);
        deferredBlockBefore = null;
        deferredBlockAfter = null;
        // Some container integrations already supplied SNBT; parse those only on the encoder worker.
        if (CreateContraptionKind.id(source)==null && itemStackNbt!=null && itemStackNbt.contains("AvilixCartAudit")) {
            var encoded=NbtSerde.fromSnbt(itemStackNbt);
            if(encoded!=null){var audit=encoded.getCompound("components").getCompound("create:minecart_contraption_data").getCompound("AvilixCartAudit");
                if(audit.hasUUID("Id"))source=CartAuditContext.prefix(audit.getUUID("Id"))+"item:0";}
        }
        if (snapshots == null) return;
        for (int i = 0; i < snapshots.length; i++) {
            net.minecraft.nbt.Tag tag = snapshots[i];
            if (tag == null) continue;
            String snbt = tag.toString();
            switch (SNAPSHOT_FIELDS[i]) {
                case BE_BEFORE -> beBefore = snbt;
                case BE_AFTER -> beAfter = snbt;
                case ITEM -> itemStackNbt = snbt;
                case ENTITY -> entityNbt = snbt;
                case SLOTS_BEFORE -> containerSlotsBefore = snbt;
                case SLOTS_AFTER -> containerSlotsAfter = snbt;
                case PLAYER_INV_BEFORE -> playerInvBefore = snbt;
                case PLAYER_INV_AFTER -> playerInvAfter = snbt;
            }
            if (SNAPSHOT_FIELDS[i] == SnapshotField.ENTITY && tag instanceof net.minecraft.nbt.CompoundTag entity
                    && CreateContraptionKind.fromSource(source) == CreateContraptionKind.UNKNOWN) {
                var kind = CreateContraptionKind.fromType(entity.getCompound("Contraption").getString("Type"));
                if (kind != CreateContraptionKind.UNKNOWN && entityUuid != null) {
                    java.util.UUID constructionId = kind == CreateContraptionKind.TRAIN && entity.hasUUID("TrainId")
                            ? entity.getUUID("TrainId") : entityUuid;
                    var cartMetadata = entity.getCompound("Contraption").getCompound(com.roften.avilixlogger.compat.create.CartAuditState.KEY);
                    if (kind == CreateContraptionKind.MINECART && cartMetadata.hasUUID("Id"))
                        constructionId = cartMetadata.getUUID("Id");
                    if((kind==CreateContraptionKind.BEARING || kind==CreateContraptionKind.OTHER)
                            && entity.getCompound("Contraption").hasUUID("AvilixContraptionId"))
                        constructionId=entity.getCompound("Contraption").getUUID("AvilixContraptionId");
                    source = kind.source(constructionId, "entity");
                }
            }
        }
        snapshots = null;
        snapshotBytes = 0;
    }

    public LogEntry copyForQueue() {
        LogEntry copy = new LogEntry();
        copy.aggregateDrop = aggregateDrop;
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
        copy.entityName = entityName;
        copy.entityUuid = entityUuid;
        copy.entityNbt = entityNbt;
        copy.itemStackNbt = itemStackNbt;
        copy.count = count;
        copy.playerInvBefore = playerInvBefore;
        copy.playerInvAfter = playerInvAfter;
        copy.containerSlotsBefore = containerSlotsBefore;
        copy.containerSlotsAfter = containerSlotsAfter;
        copy.extra = extra;
        if (snapshots != null) copy.snapshots = snapshots.clone();
        copy.snapshotBytes = snapshotBytes;
        copy.deferredBlockBefore = deferredBlockBefore;
        copy.deferredBlockAfter = deferredBlockAfter;
        copy.snapshotDelta = snapshotDelta;
        copy.rejectedSnapshotDelta = rejectedSnapshotDelta;
        return copy;
    }
}

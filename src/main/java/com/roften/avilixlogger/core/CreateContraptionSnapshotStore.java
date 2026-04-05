package com.roften.avilixlogger.core;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores a short-lived full snapshot of Create contraption entities right before they get
 * disassembled/removed. This avoids logging half-destroyed NBT where Contraption.Type is already lost.
 */
public final class CreateContraptionSnapshotStore {

    private static final long MAX_AGE_MS = 30_000L;
    private static final int MAX_ENTRIES = 1024;
    private static final ConcurrentHashMap<UUID, Entry> SNAPSHOTS = new ConcurrentHashMap<>();

    private CreateContraptionSnapshotStore() {}

    public static void remember(ServerLevel level, Entity entity, String reason) {
        if (level == null || entity == null || !isCreateContraptionEntity(entity)) return;
        try {
            String entityNbt = NbtSerde.writeEntity(level, entity);
            if (!hasCompleteContraptionSnapshot(entityNbt)) return;

            ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            SNAPSHOTS.put(entity.getUUID(), new Entry(
                    System.currentTimeMillis(),
                    level.dimension().location().toString(),
                    key != null ? key.toString() : null,
                    entityNbt,
                    reason == null ? "" : reason
            ));
            cleanup();
        } catch (Throwable ignored) {}
    }

    public static String takeFresh(UUID entityUuid, String dim, long maxAgeMs) {
        if (entityUuid == null) return null;
        Entry entry = SNAPSHOTS.remove(entityUuid);
        if (entry == null) return null;

        long age = System.currentTimeMillis() - entry.ts;
        if (age < 0L || age > Math.max(1L, maxAgeMs)) return null;
        if (dim != null && entry.dim != null && !dim.equals(entry.dim)) return null;
        return entry.entityNbt;
    }

    public static boolean hasCompleteContraptionSnapshot(String entSnbt) {
        try {
            var tag = NbtSerde.fromSnbt(entSnbt);
            if (tag == null || !tag.contains("Contraption")) return false;
            var contraption = tag.getCompound("Contraption");
            String type = contraption.getString("Type");
            return type != null && !type.isBlank();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isCreateContraptionEntity(Entity entity) {
        if (entity == null) return false;
        try {
            ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            return isCreateContraptionEntityType(key);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isCreateContraptionEntityType(ResourceLocation key) {
        if (key == null) return false;
        if (!"create".equals(key.getNamespace())) return false;
        String path = key.getPath();
        return path != null && (path.contains("contraption") || path.contains("carriage"));
    }

    private static void cleanup() {
        if (SNAPSHOTS.size() <= MAX_ENTRIES) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Entry>> it = SNAPSHOTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Entry> e = it.next();
            if ((now - e.getValue().ts) > MAX_AGE_MS) {
                it.remove();
            }
        }
        if (SNAPSHOTS.size() <= MAX_ENTRIES) return;
        it = SNAPSHOTS.entrySet().iterator();
        while (it.hasNext() && SNAPSHOTS.size() > MAX_ENTRIES) {
            it.next();
            it.remove();
        }
    }

    private record Entry(long ts, String dim, String entityType, String entityNbt, String reason) {}
}

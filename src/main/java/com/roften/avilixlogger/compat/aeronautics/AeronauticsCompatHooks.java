package com.roften.avilixlogger.compat.aeronautics;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional compat hooks for the Aeronautics / Simulated stack.
 *
 * Keep this class free from direct Aeronautics/Create/Sable references: servers without these mods must still load
 * the logger jar. Concrete compat is activated through optional mixins and namespace/stack detection.
 */
public final class AeronauticsCompatHooks {
    public static final String MOD_ID = "aeronautics";

    private static final long ACTOR_MEMORY_MS = 30L * 60L * 1000L;
    private static final long CANNON_FIRE_DEDUP_MS = 350L;

    private static final Map<String, ActorTracker.ActorRef> LAST_BLOCK_ACTOR = new ConcurrentHashMap<>();
    private static final Map<String, Long> RECENT_CANNON_FIRE_LOGS = new ConcurrentHashMap<>();

    private AeronauticsCompatHooks() {}

    public record Snapshot(
            String dim,
            BlockPos pos,
            String blockBefore,
            String beBefore,
            UUID actorUuid,
            String actorName,
            String source,
            String reason
    ) {}

    public static boolean isAeronauticsNamespace(String ns) {
        return MOD_ID.equals(ns) || "simulated".equals(ns) || "sable".equals(ns) || "sable_api".equals(ns);
    }

    public static boolean isAeronauticsBlock(BlockState state) {
        try {
            if (state == null || state.getBlock() == null) return false;
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return id != null && isAeronauticsNamespace(id.getNamespace());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isAeronauticsEntity(Entity entity) {
        try {
            if (entity == null || entity.getType() == null) return false;
            ResourceLocation id = EntityType.getKey(entity.getType());
            return id != null && isAeronauticsNamespace(id.getNamespace());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void notePlayerBlockAction(ServerLevel level, ServerPlayer player, BlockPos pos, ItemStack stack, String reason) {
        if (level == null || player == null || pos == null) return;
        try {
            RecentPlayerActionTracker.note(level, player, pos, RecentPlayerActionTracker.ActionKind.RIGHT_CLICK_BLOCK, stack == null ? ItemStack.EMPTY : stack.copy());
        } catch (Throwable ignored) {}
        try {
            ActorTracker.ActorRef ref = new ActorTracker.ActorRef(System.currentTimeMillis(), player.getUUID(), player.getName().getString());
            LAST_BLOCK_ACTOR.put(key(level, pos), ref);
            cleanupActors(System.currentTimeMillis());
        } catch (Throwable ignored) {}
    }

    public static ActorTracker.ActorRef resolveActorForBlock(ServerLevel level, BlockPos pos) {
        try {
            CauseContext.Cause c = CauseContext.peek();
            if (c != null && c.actorUuid() != null) {
                return new ActorTracker.ActorRef(System.currentTimeMillis(), c.actorUuid(), c.actorName());
            }
        } catch (Throwable ignored) {}

        try {
            RecentPlayerActionTracker.ActionRef rr = RecentPlayerActionTracker.resolveBest(level, pos, 10, 10_000L, null);
            if (rr != null && rr.actorUuid() != null) {
                return new ActorTracker.ActorRef(System.currentTimeMillis(), rr.actorUuid(), rr.actorName());
            }
        } catch (Throwable ignored) {}

        try {
            ActorTracker.ActorRef ref = LAST_BLOCK_ACTOR.get(key(level, pos));
            if (ref != null && System.currentTimeMillis() - ref.tsMs() <= ACTOR_MEMORY_MS) return ref;
        } catch (Throwable ignored) {}

        return null;
    }

    public static Snapshot captureBlockEntityBeforeFromBehaviour(Object behaviour, Player player, String reason) {
        try {
            Object beObj = readFieldRecursive(behaviour, "blockEntity");
            if (!(beObj instanceof BlockEntity be)) return null;
            if (!(be.getLevel() instanceof ServerLevel level)) return null;
            if (player instanceof ServerPlayer sp) notePlayerBlockAction(level, sp, be.getBlockPos(), sp.getMainHandItem(), reason);
            return captureBlockBefore(level, be.getBlockPos(), player, "aeronautics:" + reason, reason);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void finishBlockEntityChangeFromBehaviour(Object behaviour, Snapshot snapshot, Player player, String reason) {
        if (snapshot == null) return;
        try {
            Object beObj = readFieldRecursive(behaviour, "blockEntity");
            if (!(beObj instanceof BlockEntity be)) return;
            if (!(be.getLevel() instanceof ServerLevel level)) return;
            finishBlockEntityChange(level, snapshot, "aeronautics:" + reason, reason);
        } catch (Throwable ignored) {}
    }

    public static Snapshot captureBlockBefore(ServerLevel level, BlockPos pos, Player actor, String source, String reason) {
        if (level == null || pos == null) return null;
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity be = level.getBlockEntity(pos);
            ActorTracker.ActorRef ar = null;
            if (actor != null) ar = new ActorTracker.ActorRef(System.currentTimeMillis(), actor.getUUID(), actor.getName().getString());
            if (ar == null) ar = resolveActorForBlock(level, pos);
            return new Snapshot(
                    level.dimension().location().toString(),
                    pos.immutable(),
                    NbtSerde.writeBlockState(state),
                    be != null ? NbtSerde.writeBlockEntity(level, be) : null,
                    ar != null ? ar.actorUuid() : null,
                    ar != null ? ar.actorName() : null,
                    source,
                    reason
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void finishBlockEntityChange(ServerLevel level, Snapshot before, String source, String reason) {
        if (level == null || before == null || before.pos == null) return;
        if (!LoggerConfig.VALUES.enabled.get()) return;
        try {
            BlockState afterState0 = level.getBlockState(before.pos);
            BlockEntity beAfter0 = level.getBlockEntity(before.pos);
            String afterState = NbtSerde.writeBlockState(afterState0);
            String afterBe = beAfter0 != null ? NbtSerde.writeBlockEntity(level, beAfter0) : null;

            boolean stateChanged = before.blockBefore != null && afterState != null && !before.blockBefore.equals(afterState);
            boolean beChanged = before.beBefore != null && afterBe != null && !before.beBefore.equals(afterBe);
            if (!stateChanged && !beChanged) return;

            LogEntry e = new LogEntry();
            e.ts = System.currentTimeMillis();
            e.dim = before.dim;
            e.type = stateChanged ? ActionType.BLOCK_INTERACT : ActionType.BLOCK_ENTITY_NBT_CHANGE;
            e.actorUuid = before.actorUuid;
            e.actorName = before.actorName != null && !before.actorName.isBlank() ? before.actorName : "Aeronautics";
            e.x = before.pos.getX();
            e.y = before.pos.getY();
            e.z = before.pos.getZ();
            e.blockBefore = before.blockBefore;
            e.beBefore = before.beBefore;
            e.blockAfter = afterState;
            e.beAfter = afterBe;
            e.source = source != null ? source : before.source;
            e.extra = json("aeronautics_block_change", reason, blockId(afterState0), null, null, null);
            LoggerRuntime.storage(level).append(e);

            if (beChanged && LoggerConfig.VALUES.logContainers.get()) {
                final String dim = before.dim;
                final BlockPos pos = before.pos.immutable();
                final String beforeBe = before.beBefore;
                final String capturedAfterBe = afterBe;
                final String capturedBlockAfter = afterState;
                final UUID actorUuid = before.actorUuid;
                final String actorName = before.actorName != null && !before.actorName.isBlank() ? before.actorName : "Aeronautics";
                final var registryAccess = level.registryAccess();
                final LogStorage storage = LoggerRuntime.storage(level);
                AsyncLogProcessor.submit(com.roften.avilixlogger.core.PayloadSizeEstimator.estimateStrings(beforeBe, capturedAfterBe), () -> {
                    var diffs = InventoryDiffUtil.diff(beforeBe, capturedAfterBe, registryAccess);
                    if (diffs == null || diffs.isEmpty()) return;
                    long ts = System.currentTimeMillis();
                    for (var d : diffs) {
                        LogEntry de = new LogEntry();
                        de.ts = ts;
                        de.dim = dim;
                        de.type = (d.deltaCount() > 0) ? ActionType.CONTAINER_PUT : ActionType.CONTAINER_TAKE;
                        de.actorUuid = actorUuid;
                        de.actorName = actorName;
                        de.x = pos.getX();
                        de.y = pos.getY();
                        de.z = pos.getZ();
                        de.blockAfter = capturedBlockAfter;
                        de.count = Math.abs(d.deltaCount());
                        try {
                            ItemStack st = d.representative().copy();
                            st.setCount(Math.max(1, Math.abs(d.deltaCount())));
                            de.itemStackNbt = NbtSerde.writeItemStack(st, registryAccess);
                        } catch (Throwable ignored) {}
                        de.source = source;
                        de.extra = json("aeronautics_inventory_delta", reason, null, null, null, null);
                        storage.append(de);
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    public static boolean logMountedCannonProjectile(Object blockEntityObject, Entity projectile) {
        boolean added = false;
        try {
            if (blockEntityObject instanceof BlockEntity be && be.getLevel() != null) {
                added = be.getLevel().addFreshEntity(projectile);
                if (added) logMountedCannonFire(be, projectile);
                return added;
            }
        } catch (Throwable ignored) {}
        try {
            if (projectile != null && projectile.level() != null) return projectile.level().addFreshEntity(projectile);
        } catch (Throwable ignored) {}
        return false;
    }

    public static void logMountedCannonFire(BlockEntity be, Entity projectile) {
        if (be == null || !(be.getLevel() instanceof ServerLevel level) || projectile == null) return;
        if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logBlocks.get()) return;
        try {
            BlockPos pos = be.getBlockPos();
            long now = System.currentTimeMillis();
            String dedupKey = key(level, pos) + ":fire";
            Long prev = RECENT_CANNON_FIRE_LOGS.get(dedupKey);
            if (prev != null && now - prev >= 0 && now - prev < CANNON_FIRE_DEDUP_MS) return;
            RECENT_CANNON_FIRE_LOGS.put(dedupKey, now);
            if (RECENT_CANNON_FIRE_LOGS.size() > 4096) {
                long cutoff = now - 30_000L;
                RECENT_CANNON_FIRE_LOGS.entrySet().removeIf(e -> e.getValue() == null || e.getValue() < cutoff);
            }

            ActorTracker.ActorRef ar = resolveActorForBlock(level, pos);
            if (ar != null && ar.actorUuid() != null) {
                try {
                    projectile.getPersistentData().putString("avilixlogger_owner_uuid", ar.actorUuid().toString());
                    if (ar.actorName() != null && !ar.actorName().isBlank()) projectile.getPersistentData().putString("avilixlogger_owner_name", ar.actorName());
                    projectile.getPersistentData().putString("avilixlogger_owner_src", "aeronautics_mounted_potato_cannon");
                } catch (Throwable ignored) {}
            }

            LogEntry e = new LogEntry();
            e.ts = now;
            e.dim = level.dimension().location().toString();
            e.type = ActionType.BLOCK_INTERACT;
            e.actorUuid = ar != null ? ar.actorUuid() : null;
            e.actorName = ar != null && ar.actorName() != null && !ar.actorName().isBlank() ? ar.actorName() : "Aeronautics";
            e.x = pos.getX();
            e.y = pos.getY();
            e.z = pos.getZ();
            e.blockAfter = NbtSerde.writeBlockState(be.getBlockState());
            e.beAfter = NbtSerde.writeBlockEntity(level, be);
            try {
                e.entityType = EntityType.getKey(projectile.getType()).toString();
                e.entityUuid = projectile.getUUID();
                e.entityNbt = NbtSerde.writeEntity(level, projectile);
            } catch (Throwable ignored) {}
            e.source = "aeronautics:mounted_potato_cannon";
            e.extra = json("mounted_potato_cannon_fire", "redstone_fire", blockId(be.getBlockState()), e.entityType, null, null);
            LoggerRuntime.storage(level).append(e);
        } catch (Throwable ignored) {}
    }

    private static String key(ServerLevel level, BlockPos pos) {
        String dim = "?";
        try { dim = level.dimension().location().toString(); } catch (Throwable ignored) {}
        return dim + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static void cleanupActors(long now) {
        if (LAST_BLOCK_ACTOR.size() <= 8192) return;
        LAST_BLOCK_ACTOR.entrySet().removeIf(e -> e.getValue() == null || now - e.getValue().tsMs() > ACTOR_MEMORY_MS);
    }

    private static String blockId(BlockState state) {
        try {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return id != null ? id.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readFieldRecursive(Object target, String fieldName) {
        if (target == null || fieldName == null) return null;
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static String json(String kind, String reason, String blockId, String entityId, String itemId, String note) {
        return "{\"kind\":\"" + esc(kind) + "\",\"reason\":\"" + esc(reason) + "\",\"blockId\":\"" + esc(blockId) + "\",\"entityId\":\"" + esc(entityId) + "\",\"itemId\":\"" + esc(itemId) + "\",\"note\":\"" + esc(note) + "\"}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}

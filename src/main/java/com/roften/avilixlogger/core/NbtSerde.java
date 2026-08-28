package com.roften.avilixlogger.core;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized NBT (SNBT) serialization utilities.
 *
 * IMPORTANT: 1.21+ moved a lot of item/block serialization to rely on HolderLookup.Provider.
 * This class keeps reflection fallbacks to survive minor NeoForge/Mojang signature shifts.
 */
public final class NbtSerde {

    /**
     * Reflection on modded classes can be expensive and can also fail if optional dependencies
     * are referenced in method signatures. Cache lookups to avoid repeated scanning.
     */
    private static final ConcurrentHashMap<MethodKey, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();

    private NbtSerde() {}

    public static CompoundTag fromSnbt(String snbt) {
        if (snbt == null || snbt.isEmpty()) return null;
        try {
            return TagParser.parseTag(snbt);
        } catch (CommandSyntaxException e) {
            return null;
        }
    }

    public static String toSnbt(CompoundTag tag) {
        return tag == null ? null : tag.toString();
    }

    public static String writeBlockState(BlockState state) {
        return toSnbt(NbtUtils.writeBlockState(state));
    }

    public static BlockState readBlockState(ServerLevel level, String snbt) {
        CompoundTag tag = fromSnbt(snbt);
        if (tag == null) return null;
        // In 1.21+, readBlockState takes HolderGetter<Block> which RegistryLookup implements.
        var getter = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        return NbtUtils.readBlockState(getter, tag);
    }

    /** Snapshot a block entity including its full metadata. */
    public static String writeBlockEntity(ServerLevel level, BlockEntity be) {
        if (be == null) return null;

        // Hot path for MC 1.21.x: avoid the reflection-heavy invokeBlockEntitySave chain.
        // Reflection fallback stays below for compatibility with mapping/API edge cases.
        try {
            CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
            if (tag != null) return toSnbt(tag);
        } catch (Throwable ignored) {
        }

        CompoundTag tag = invokeBlockEntitySave(level, be);
        return toSnbt(tag);
    }

    /** Restore a block entity snapshot (must already exist at pos after state set). */
    public static boolean readBlockEntity(ServerLevel level, BlockPos pos, String beSnbt) {
        if (beSnbt == null || beSnbt.isEmpty()) return false;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return false;

        CompoundTag tag = fromSnbt(beSnbt);
        if (tag == null) return false;
        // Ensure coords (some serializers rely on them)
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());

        if (!invokeBlockEntityLoad(level, be, tag)) return false;
        be.setChanged();
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, 3);
        return true;
    }

    /** Entity snapshot without hard dependency on exact save signature. */
    public static String writeEntity(ServerLevel level, Entity ent) {
        if (ent == null) return null;
        CompoundTag tag = new CompoundTag();
        try {
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType());
            if (key == null) return null;
            tag.putString("id", key.toString());
            // save() intentionally refuses removed entities and passengers. Logger snapshots must still
            // be complete in EntityLeaveLevelEvent, therefore use the unconditional payload writer.
            ent.saveWithoutId(tag);
        } catch (Throwable ignored) {
            return null;
        }

        // Create contraptions are a special case: during removal/disassembly the generic save path may
        // produce a half-empty snapshot where Contraption.Type is already missing. For rollback we need
        // the full nested Contraption NBT. Enrich it while the entity is still alive.
        try {
            if (looksLikeCreateContraptionEntity(ent)) {
                CompoundTag contraptionTag = extractCreateContraptionNbt(level, ent);
                if (contraptionTag != null && !contraptionTag.isEmpty()) {
                    tag.put("Contraption", contraptionTag);
                }
                invokeCreateWriteAdditional(level, ent, tag);
            }
        } catch (Throwable ignored) {}

        return toSnbt(tag);
    }

    public record EntityRestoreResult(boolean success, String reason, Entity entity, int affected) {
        static EntityRestoreResult ok(Entity entity, int affected) {
            return new EntityRestoreResult(true, "", entity, Math.max(1, affected));
        }

        static EntityRestoreResult fail(String reason) {
            return new EntityRestoreResult(false, reason == null || reason.isBlank() ? "entity_restore_failed" : reason, null, 0);
        }
    }

    /** Validate an entity snapshot without constructing or adding anything to the world. */
    public static String validateEntitySnapshot(String entityTypeId, UUID expectedUuid, String entSnbt) {
        ResourceLocation key = ResourceLocation.tryParse(entityTypeId);
        if (key == null || BuiltInRegistries.ENTITY_TYPE.getOptional(key).isEmpty()) return "unknown_entity_type";
        CompoundTag tag = fromSnbt(entSnbt);
        if (tag == null || tag.isEmpty()) return "invalid_entity_nbt";
        if (isUnsafeEntityRollback(key, tag)) {
            if (!CreateContraptionSnapshotStore.hasCompleteContraptionSnapshot(entSnbt)) return "incomplete_create_snapshot";
        }
        return validateEntityTree(tag, key, expectedUuid, true);
    }

    /**
     * Restore exact entity state, including UUID, precise position/rotation/motion and passengers.
     * No approximate block-position fallback is used: an incomplete snapshot is rejected explicitly.
     */
    public static EntityRestoreResult restoreEntityFromSnapshot(ServerLevel level, String entityTypeId,
                                                                  UUID expectedUuid, String entSnbt) {
        String invalid = validateEntitySnapshot(entityTypeId, expectedUuid, entSnbt);
        if (invalid != null) return EntityRestoreResult.fail(invalid);

        ResourceLocation key = ResourceLocation.tryParse(entityTypeId);
        CompoundTag tag = fromSnbt(entSnbt);
        if (key == null || tag == null || isUnsafeEntityRollback(key, tag)) {
            return EntityRestoreResult.fail("unsafe_entity_snapshot");
        }

        tag = tag.copy();
        tag.putString("id", key.toString());
        if (expectedUuid != null) tag.putUUID("UUID", expectedUuid);

        Set<UUID> snapshotUuids = new HashSet<>();
        collectEntityUuids(tag, snapshotUuids);
        for (UUID uuid : snapshotUuids) {
            if (uuid != null && level.getEntity(uuid) != null) return EntityRestoreResult.fail("entity_uuid_collision");
        }

        Entity root;
        try {
            root = EntityType.loadEntityRecursive(tag, level, entity -> entity);
        } catch (Throwable ignored) {
            return EntityRestoreResult.fail("entity_nbt_load_failed");
        }
        if (root == null) return EntityRestoreResult.fail("entity_nbt_load_failed");
        if (expectedUuid != null && !expectedUuid.equals(root.getUUID())) return EntityRestoreResult.fail("entity_uuid_mismatch");

        List<Entity> tree = root.getSelfAndPassengers().toList();
        try {
            if (!level.tryAddFreshEntityWithPassengers(root)) return EntityRestoreResult.fail("entity_add_rejected");
            for (Entity entity : tree) {
                if (level.getEntity(entity.getUUID()) != entity) {
                    removeRestoredEntityTree(level, tree);
                    return EntityRestoreResult.fail("entity_add_incomplete");
                }
            }
            return EntityRestoreResult.ok(root, tree.size());
        } catch (Throwable ignored) {
            removeRestoredEntityTree(level, tree);
            return EntityRestoreResult.fail("entity_add_exception");
        }
    }

    /**
     * Best-effort rollback for Create contraption entities.
     *
     * Instead of respawning a moving contraption entity (which is brittle and often client-crashy),
     * we reconstruct the Create contraption from the saved NBT and ask Create to place its blocks back
     * into the world using the entity's saved transform.
     */
    public static EntityRestoreResult restoreCreateContraptionAsBlocks(ServerLevel level, String entityTypeId,
                                                                        UUID expectedUuid, String entSnbt) {
        List<WorldBlockBackup> backups = List.of();
        try {
            ResourceLocation key = ResourceLocation.tryParse(entityTypeId);
            CompoundTag entityTag = fromSnbt(entSnbt);
            if (key == null || entityTag == null || !isUnsafeEntityRollback(key, entityTag)) return EntityRestoreResult.fail("not_create_snapshot");
            Optional<EntityType<?>> typeOpt = BuiltInRegistries.ENTITY_TYPE.getOptional(key);
            if (typeOpt.isEmpty()) return EntityRestoreResult.fail("unknown_entity_type");

            CompoundTag contraptionTag = entityTag.getCompound("Contraption");
            if (contraptionTag == null || contraptionTag.isEmpty()) return EntityRestoreResult.fail("incomplete_create_snapshot");
            String contraptionType = contraptionTag.getString("Type");
            if (contraptionType == null || contraptionType.isBlank()) return EntityRestoreResult.fail("incomplete_create_snapshot");
            if (expectedUuid != null && entityTag.hasUUID("UUID") && !expectedUuid.equals(entityTag.getUUID("UUID"))) {
                return EntityRestoreResult.fail("entity_uuid_mismatch");
            }

            Object contraption = createCreateContraptionFromNbt(level, contraptionTag);
            if (contraption == null) return EntityRestoreResult.fail("create_nbt_load_failed");

            Entity shell = instantiateCreateRollbackShell(level, key, typeOpt.get(), entityTag);
            if (shell == null) return EntityRestoreResult.fail("create_transform_unavailable");

            Object transform = invokeNoArg(shell, "makeStructureTransform");
            if (transform == null) return EntityRestoreResult.fail("create_transform_unavailable");

            List<CreateTarget> targets = collectCreateTargets(contraption, transform);
            if (targets.isEmpty()) return EntityRestoreResult.fail("create_blocks_missing");
            for (CreateTarget target : targets) {
                if (target.pos == null || !level.isInWorldBounds(target.pos)) return EntityRestoreResult.fail("create_target_out_of_world");
                if (!level.hasChunkAt(target.pos)) return EntityRestoreResult.fail("create_chunk_unloaded");
            }

            backups = new ArrayList<>(targets.size());
            for (CreateTarget target : targets) {
                BlockPos pos = target.pos;
                backups.add(new WorldBlockBackup(pos, level.getBlockState(pos),
                        writeBlockEntity(level, level.getBlockEntity(pos)), ContainerSlotSnapshot.snapshot(level, pos)));
            }

            try {
                invokeNamed(contraption, "stop", level);
            } catch (Throwable ignored) {}

            if (!invokeNamed(contraption, "addBlocksToWorld", level, transform)) {
                restoreWorldBackups(level, backups);
                return EntityRestoreResult.fail("create_place_failed");
            }
            for (CreateTarget target : targets) {
                BlockState actual = level.getBlockState(target.pos);
                if (target.expectedState != null && actual.getBlock() != target.expectedState.getBlock()) {
                    restoreWorldBackups(level, backups);
                    return EntityRestoreResult.fail("create_verification_failed");
                }
            }
            return EntityRestoreResult.ok(null, targets.size());
        } catch (Throwable ignored) {
            restoreWorldBackups(level, backups);
            return EntityRestoreResult.fail("create_restore_exception");
        }
    }

    public static String writeItemStack(ItemStack stack, HolderLookup.Provider provider) {
        if (stack == null || stack.isEmpty()) return null;
        CompoundTag tag = invokeItemStackSave(stack, provider);
        return toSnbt(tag);
    }

    /**
     * Very cheap serializer for hot events such as item pickup/drop.
     * Plain vanilla stacks dominate pickup spam; full ItemStack#save() is kept for all risky stacks.
     */
    public static String writeItemStackHotPath(ItemStack stack, HolderLookup.Provider provider) {
        if (stack == null || stack.isEmpty()) return null;
        if (canUseSimpleItemStackSnbt(stack)) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null) {
                return "{id:\"" + id + "\",count:" + stack.getCount() + "}";
            }
        }
        return writeItemStack(stack, provider);
    }

    private static boolean canUseSimpleItemStackSnbt(ItemStack stack) {
        try {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null || !"minecraft".equals(id.getNamespace())) return false;
            try { if (stack.isDamaged()) return false; } catch (Throwable ignored) {}
            try { if (stack.has(DataComponents.CUSTOM_DATA)) return false; } catch (Throwable ignored) {}
            try { if (stack.has(DataComponents.CUSTOM_NAME)) return false; } catch (Throwable ignored) {}
            try { if (stack.has(DataComponents.ITEM_NAME)) return false; } catch (Throwable ignored) {}
            try { if (stack.has(DataComponents.ENCHANTMENTS)) return false; } catch (Throwable ignored) {}
            try { if (stack.has(DataComponents.STORED_ENCHANTMENTS)) return false; } catch (Throwable ignored) {}
            try { if (stack.has(DataComponents.CONTAINER)) return false; } catch (Throwable ignored) {}
            try { if (stack.has(DataComponents.CONTAINER_LOOT)) return false; } catch (Throwable ignored) {}
            try { if (stack.has(DataComponents.BLOCK_ENTITY_DATA)) return false; } catch (Throwable ignored) {}
            try { if (stack.has(DataComponents.ENTITY_DATA)) return false; } catch (Throwable ignored) {}
            try { if (stack.has(DataComponents.BLOCK_STATE)) return false; } catch (Throwable ignored) {}
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isUnsafeEntityRollback(ResourceLocation key, CompoundTag tag) {
        if (key == null) return true;
        String ns = key.getNamespace();
        String path = key.getPath();
        if ("create".equals(ns)) {
            if (path != null && (path.contains("contraption") || path.contains("carriage"))) {
                return true;
            }
            try {
                if (tag != null && (tag.contains("Contraption") || tag.contains("contraption")
                        || tag.contains("Carriage") || tag.contains("carriage"))) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    public static boolean isCreateContraptionSnapshot(String entityTypeId, String entSnbt) {
        ResourceLocation key = ResourceLocation.tryParse(entityTypeId);
        CompoundTag tag = fromSnbt(entSnbt);
        return key != null && tag != null && isUnsafeEntityRollback(key, tag);
    }

    private static boolean looksLikeCreateContraptionEntity(Entity ent) {
        if (ent == null) return false;
        try {
            ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType());
            if (key == null || !"create".equals(key.getNamespace())) return false;
            String path = key.getPath();
            return path != null && (path.contains("contraption") || path.contains("carriage"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static CompoundTag extractCreateContraptionNbt(ServerLevel level, Entity ent) {
        if (level == null || ent == null) return null;
        try {
            Object contraption = readField(ent, "contraption");
            if (contraption == null) return null;
            Method m = findCompatibleMethod(contraption.getClass(), "writeNBT", level.registryAccess(), Boolean.FALSE);
            if (m == null) return null;
            Object out = m.invoke(contraption, level.registryAccess(), Boolean.FALSE);
            return out instanceof CompoundTag t ? t : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void invokeCreateWriteAdditional(ServerLevel level, Entity ent, CompoundTag out) {
        if (level == null || ent == null || out == null) return;
        try {
            Method m = findCompatibleMethod(ent.getClass(), "writeAdditional", out, level.registryAccess(), Boolean.FALSE);
            if (m != null) {
                m.invoke(ent, out, level.registryAccess(), Boolean.FALSE);
                return;
            }
        } catch (Throwable ignored) {}

        try {
            Method m = findCompatibleMethod(ent.getClass(), "addAdditionalSaveData", out);
            if (m != null) {
                m.invoke(ent, out);
            }
        } catch (Throwable ignored) {}
    }

    private static Object createCreateContraptionFromNbt(ServerLevel level, CompoundTag contraptionTag) {
        try {
            Class<?> contraptionClass = Class.forName("com.simibubi.create.content.contraptions.Contraption");
            for (Method m : contraptionClass.getMethods()) {
                if (!m.getName().equals("fromNBT") || m.getParameterCount() != 3) continue;
                Object[] args = new Object[]{level, contraptionTag, Boolean.FALSE};
                return m.invoke(null, args);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Entity instantiateCreateRollbackShell(ServerLevel level, ResourceLocation key, EntityType<?> type,
                                                         CompoundTag entityTag) {
        try {
            String className = createRollbackShellClassName(key);
            if (className == null) return null;

            Class<?> cls = Class.forName(className);
            Constructor<?> ctor = cls.getConstructor(EntityType.class, net.minecraft.world.level.Level.class);
            Object raw = ctor.newInstance(type, level);
            if (!(raw instanceof Entity shell)) return null;

            var pos = entityTag.getList("Pos", Tag.TAG_DOUBLE);
            if (pos.size() < 3) return null;
            double px = pos.getDouble(0);
            double py = pos.getDouble(1);
            double pz = pos.getDouble(2);
            if (!Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz)) return null;

            float yaw = 0.0F;
            float pitch = 0.0F;
            try {
                var rot = entityTag.getList("Rotation", 5);
                if (rot.size() >= 2) {
                    yaw = rot.getFloat(0);
                    pitch = rot.getFloat(1);
                }
            } catch (Throwable ignored) {}

            shell.moveTo(px, py, pz, yaw, pitch);

            String path = key.getPath();
            if (path != null && (path.contains("oriented_contraption") || path.contains("carriage_contraption"))) {
                Object dir = readDirectionTag(entityTag, "InitialOrientation");
                if (dir != null) invokeNamed(shell, "setInitialOrientation", dir);
                writeFieldIfPresent(shell, "yaw", entityTag.getFloat("Yaw"));
                writeFieldIfPresent(shell, "pitch", entityTag.getFloat("Pitch"));
            }
            if (path != null && path.contains("controlled_contraption")) {
                Object axis = readAxisTag(entityTag, "Axis");
                if (axis != null) invokeNamed(shell, "setRotationAxis", axis);
                invokeNamed(shell, "setAngle", entityTag.getFloat("Angle"));
            }
            if (path != null && path.contains("carriage_contraption")) {
                try {
                    writeFieldIfPresent(shell, "trainId", entityTag.getUUID("TrainId"));
                } catch (Throwable ignored) {}
                try {
                    writeFieldIfPresent(shell, "carriageIndex", entityTag.getInt("CarriageIndex"));
                } catch (Throwable ignored) {}
            }

            return shell;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String validateEntityTree(CompoundTag tag, ResourceLocation rootType, UUID expectedRootUuid, boolean root) {
        if (tag == null || tag.isEmpty()) return "invalid_entity_nbt";
        String rawId = tag.getString("id");
        ResourceLocation id = rawId == null || rawId.isBlank() ? rootType : ResourceLocation.tryParse(rawId);
        if (id == null || BuiltInRegistries.ENTITY_TYPE.getOptional(id).isEmpty()) return "unknown_entity_type";
        if (root && rootType != null && !rootType.equals(id)) return "entity_type_mismatch";
        if (!tag.hasUUID("UUID")) return "entity_missing_uuid";
        if (root && expectedRootUuid != null && !expectedRootUuid.equals(tag.getUUID("UUID"))) return "entity_uuid_mismatch";

        ListTag pos = tag.getList("Pos", Tag.TAG_DOUBLE);
        if (pos.size() < 3 || !Double.isFinite(pos.getDouble(0)) || !Double.isFinite(pos.getDouble(1)) || !Double.isFinite(pos.getDouble(2))) {
            return "entity_missing_position";
        }
        ListTag rotation = tag.getList("Rotation", Tag.TAG_FLOAT);
        if (rotation.size() < 2 || !Float.isFinite(rotation.getFloat(0)) || !Float.isFinite(rotation.getFloat(1))) {
            return "entity_missing_rotation";
        }

        if (tag.contains("Passengers", Tag.TAG_LIST)) {
            ListTag passengers = tag.getList("Passengers", Tag.TAG_COMPOUND);
            for (int i = 0; i < passengers.size(); i++) {
                String invalid = validateEntityTree(passengers.getCompound(i), null, null, false);
                if (invalid != null) return invalid;
            }
        }
        return null;
    }

    private static void collectEntityUuids(CompoundTag tag, Set<UUID> out) {
        if (tag == null || out == null) return;
        if (tag.hasUUID("UUID")) out.add(tag.getUUID("UUID"));
        if (!tag.contains("Passengers", Tag.TAG_LIST)) return;
        ListTag passengers = tag.getList("Passengers", Tag.TAG_COMPOUND);
        for (int i = 0; i < passengers.size(); i++) collectEntityUuids(passengers.getCompound(i), out);
    }

    private static void removeRestoredEntityTree(ServerLevel level, List<Entity> tree) {
        if (level == null || tree == null) return;
        for (Entity entity : tree) {
            try {
                if (entity != null && level.getEntity(entity.getUUID()) == entity) entity.discard();
            } catch (Throwable ignored) {}
        }
    }

    private static List<CreateTarget> collectCreateTargets(Object contraption, Object transform) {
        Object rawBlocks = readField(contraption, "blocks");
        if (!(rawBlocks instanceof Map<?, ?> blocks) || blocks.isEmpty()) return List.of();

        ArrayList<CreateTarget> out = new ArrayList<>(blocks.size());
        HashSet<BlockPos> unique = new HashSet<>();
        for (Map.Entry<?, ?> entry : blocks.entrySet()) {
            if (!(entry.getKey() instanceof BlockPos localPos)) return List.of();
            Object transformed = invokeCompatible(transform, "apply", localPos);
            if (!(transformed instanceof BlockPos worldPos) || !unique.add(worldPos)) return List.of();

            BlockState expected = null;
            Object info = entry.getValue();
            Object rawState = invokeNoArg(info, "state");
            if (!(rawState instanceof BlockState)) rawState = readField(info, "state");
            if (rawState instanceof BlockState state) {
                Object transformedState = invokeCompatible(transform, "apply", state);
                expected = transformedState instanceof BlockState bs ? bs : state;
            }
            out.add(new CreateTarget(worldPos.immutable(), expected));
        }
        return out;
    }

    private static void restoreWorldBackups(ServerLevel level, List<WorldBlockBackup> backups) {
        if (level == null || backups == null) return;
        for (WorldBlockBackup backup : backups) {
            try { level.setBlock(backup.pos, backup.state, 2); } catch (Throwable ignored) {}
        }
        for (WorldBlockBackup backup : backups) {
            try {
                if (backup.beSnbt != null) readBlockEntity(level, backup.pos, backup.beSnbt);
                if (backup.containerSnbt != null) ContainerSlotSnapshot.apply(level, backup.pos, backup.containerSnbt);
            } catch (Throwable ignored) {}
        }
    }

    private static Object invokeCompatible(Object target, String name, Object... args) {
        if (target == null || name == null) return null;
        Method method = findCompatibleMethod(target.getClass(), name, args);
        if (method == null) return null;
        try {
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private record CreateTarget(BlockPos pos, BlockState expectedState) {}
    private record WorldBlockBackup(BlockPos pos, BlockState state, String beSnbt, String containerSnbt) {}

    private static String createRollbackShellClassName(ResourceLocation key) {
        if (key == null) return null;
        if (!"create".equals(key.getNamespace())) return null;
        String path = key.getPath();
        if (path == null) return null;
        if (path.contains("carriage_contraption")) {
            return "com.simibubi.create.content.trains.entity.CarriageContraptionEntity";
        }
        if (path.contains("controlled_contraption")) {
            return "com.simibubi.create.content.contraptions.ControlledContraptionEntity";
        }
        if (path.contains("oriented_contraption")) {
            return "com.simibubi.create.content.contraptions.OrientedContraptionEntity";
        }
        return null;
    }

    private static Object readDirectionTag(CompoundTag tag, String key) {
        try {
            if (!tag.contains(key)) return null;
            String raw = tag.getString(key);
            if (raw != null && !raw.isBlank()) {
                return Enum.valueOf(net.minecraft.core.Direction.class, raw.toUpperCase(java.util.Locale.ROOT));
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object readAxisTag(CompoundTag tag, String key) {
        try {
            if (!tag.contains(key)) return null;
            String raw = tag.getString(key);
            if (raw != null && !raw.isBlank()) {
                return Enum.valueOf(net.minecraft.core.Direction.Axis.class, raw.toUpperCase(java.util.Locale.ROOT));
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean invokeNamed(Object target, String name, Object... args) {
        if (target == null || name == null) return false;
        Method m = findCompatibleMethod(target.getClass(), name, args);
        if (m == null) return false;
        try {
            m.invoke(target, args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null || name == null) return null;
        Method m = findCompatibleMethod(target.getClass(), name);
        if (m == null) return null;
        try {
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findCompatibleMethod(Class<?> cls, String name, Object... args) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != args.length) continue;
                boolean ok = true;
                for (int i = 0; i < params.length; i++) {
                    Object arg = args[i];
                    if (arg == null) continue;
                    if (params[i].isPrimitive()) {
                        Class<?> wrapper = primitiveWrapper(params[i]);
                        if (wrapper == null || !wrapper.isInstance(arg)) {
                            ok = false;
                            break;
                        }
                    } else if (!params[i].isAssignableFrom(arg.getClass())) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {}
                return m;
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Class<?> primitiveWrapper(Class<?> primitive) {
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == short.class) return Short.class;
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == float.class) return Float.class;
        if (primitive == double.class) return Double.class;
        if (primitive == char.class) return Character.class;
        return null;
    }

    private static Object readField(Object target, String fieldName) {
        if (target == null || fieldName == null) return null;
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ex) {
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static void writeFieldIfPresent(Object target, String fieldName, Object value) {
        if (target == null || fieldName == null) return;
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ex) {
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return;
            }
        }
    }

    public static ItemStack readItemStack(String snbt, HolderLookup.Provider provider) {
        CompoundTag tag = fromSnbt(snbt);
        if (tag == null) return ItemStack.EMPTY;

        // 1.21+: ItemStack.parse(provider, tag) returns Optional<ItemStack>
        try {
            Method parse = ItemStack.class.getMethod("parse", HolderLookup.Provider.class, CompoundTag.class);
            Object opt = parse.invoke(null, provider, tag);
            if (opt instanceof java.util.Optional<?> o && o.isPresent() && o.get() instanceof ItemStack is) {
                return is;
            }
        } catch (Throwable ignored) {}

        // Legacy fallback
        try {
            Method of = ItemStack.class.getMethod("of", CompoundTag.class);
            Object is = of.invoke(null, tag);
            if (is instanceof ItemStack s) return s;
        } catch (Throwable ignored) {}

        return ItemStack.EMPTY;
    }

    private static CompoundTag invokeBlockEntitySave(ServerLevel level, BlockEntity be) {
        // NOTE: In Mojang/NeoForge mappings many BE save methods are NOT public (protected/package-private).
        // Using Class#getMethod() only finds public members, which results in empty/minimal tags for containers.
        // We therefore try both public and declared methods (walking the class hierarchy) and force accessibility.

        // 1) Newer mappings sometimes expose a no-arg saveWithFullMetadata().
        try {
            Method m = findAnyMethod(be.getClass(), "saveWithFullMetadata");
            Object r = m.invoke(be);
            if (r instanceof CompoundTag t) return t;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }

        // Preferred: saveWithFullMetadata(HolderLookup.Provider)
        try {
            Method m = findAnyMethod(be.getClass(), "saveWithFullMetadata", HolderLookup.Provider.class);
            Object r = m.invoke(be, level.registryAccess());
            if (r instanceof CompoundTag t) return t;
        } catch (NoSuchMethodException ignored) {
        } catch (IllegalAccessException | InvocationTargetException ignored) {
        }

        // 2) Some versions use saveWithId(..) to include the BE type id.
        try {
            Method m = findAnyMethod(be.getClass(), "saveWithId", HolderLookup.Provider.class);
            Object r = m.invoke(be, level.registryAccess());
            if (r instanceof CompoundTag t) return t;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }

        try {
            Method m = findAnyMethod(be.getClass(), "saveWithId");
            Object r = m.invoke(be);
            if (r instanceof CompoundTag t) return t;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }

        // Alternative: saveWithoutMetadata(HolderLookup.Provider)
        try {
            Method m = findAnyMethod(be.getClass(), "saveWithoutMetadata", HolderLookup.Provider.class);
            Object r = m.invoke(be, level.registryAccess());
            if (r instanceof CompoundTag t) return t;
        } catch (Throwable ignored) {}

        // Alternative: saveWithoutMetadata() no-arg
        try {
            Method m = findAnyMethod(be.getClass(), "saveWithoutMetadata");
            Object r = m.invoke(be);
            if (r instanceof CompoundTag t) return t;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }

        // 3) Last-ditch: build a tag and call saveAdditional(..) if present.
        try {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString());
            tag.putInt("x", be.getBlockPos().getX());
            tag.putInt("y", be.getBlockPos().getY());
            tag.putInt("z", be.getBlockPos().getZ());

            try {
                Method m = findAnyMethod(be.getClass(), "saveAdditional", CompoundTag.class, HolderLookup.Provider.class);
                m.invoke(be, tag, level.registryAccess());
                return tag;
            } catch (NoSuchMethodException ignored) {
            }

            try {
                Method m = findAnyMethod(be.getClass(), "saveAdditional", CompoundTag.class);
                m.invoke(be, tag);
                return tag;
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable ignored) {
        }

        // Last resort: minimal snapshot
        CompoundTag tag = new CompoundTag();
        tag.putString("id", net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString());
        tag.putInt("x", be.getBlockPos().getX());
        tag.putInt("y", be.getBlockPos().getY());
        tag.putInt("z", be.getBlockPos().getZ());
        return tag;
    }

    /** Finds a method even if it's protected/package-private by walking the class hierarchy. */
    private static Method findAnyMethod(Class<?> cls, String name, Class<?>... params) throws NoSuchMethodException {
        MethodKey key = new MethodKey(cls, name, params);
        Optional<Method> cached = METHOD_CACHE.get(key);
        if (cached != null) {
            if (cached.isPresent()) return cached.get();
            throw new NoSuchMethodException(name);
        }

        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                METHOD_CACHE.put(key, Optional.of(m));
                return m;
            } catch (NoSuchMethodException ignored) {
                // continue
            } catch (LinkageError | TypeNotPresentException e) {
                // Some modded classes can reference optional dependencies (e.g. CC:Tweaked API)
                // in method signatures. Reflection may throw here while resolving parameter
                // or return types. We must not crash the server if an optional dependency
                // isn't installed.
            }
            try {
                Method m = c.getMethod(name, params);
                m.setAccessible(true);
                METHOD_CACHE.put(key, Optional.of(m));
                return m;
            } catch (NoSuchMethodException ignored) {
                // continue
            } catch (LinkageError | TypeNotPresentException e) {
                // Same story as above: skip classes we can't introspect safely.
            }
            c = c.getSuperclass();
        }

        METHOD_CACHE.put(key, Optional.empty());
        throw new NoSuchMethodException(name);
    }

    private record MethodKey(Class<?> cls, String name, Class<?>[] params) {
        // Compact canonical constructor (must match record components exactly).
        // Normalizes null into an empty array so the cache key is stable.
        MethodKey {
            if (params == null) params = new Class<?>[0];
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MethodKey other)) return false;
            return cls == other.cls && name.equals(other.name) && Arrays.equals(params, other.params);
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(cls);
            result = 31 * result + name.hashCode();
            result = 31 * result + Arrays.hashCode(params);
            return result;
        }
    }

    private static boolean invokeBlockEntityLoad(ServerLevel level, BlockEntity be, CompoundTag tag) {
        // Preferred: loadWithComponents(tag, provider)
        try {
            Method m = BlockEntity.class.getMethod("loadWithComponents", CompoundTag.class, HolderLookup.Provider.class);
            m.invoke(be, tag, level.registryAccess());
            return true;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }

        // Alternative: load(tag, provider)
        try {
            Method m = BlockEntity.class.getMethod("load", CompoundTag.class, HolderLookup.Provider.class);
            m.invoke(be, tag, level.registryAccess());
            return true;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }

        // Legacy: load(tag)
        try {
            Method m = BlockEntity.class.getMethod("load", CompoundTag.class);
            m.invoke(be, tag);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static CompoundTag invokeItemStackSave(ItemStack stack, HolderLookup.Provider provider) {
        // 1.21+: save(provider) -> CompoundTag
        try {
            Method m = ItemStack.class.getMethod("save", HolderLookup.Provider.class);
            Object r = m.invoke(stack, provider);
            if (r instanceof CompoundTag t) return t;
        } catch (Throwable ignored) {}

        // Legacy: save(CompoundTag)
        CompoundTag tag = new CompoundTag();
        try {
            Method m = ItemStack.class.getMethod("save", CompoundTag.class);
            m.invoke(stack, tag);
            return tag;
        } catch (Throwable ignored) {}

        return tag;
    }
}

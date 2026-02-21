package com.roften.avilixlogger.core;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
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
        CompoundTag tag = invokeBlockEntitySave(level, be);
        return toSnbt(tag);
    }

    /** Restore a block entity snapshot (must already exist at pos after state set). */
    public static void readBlockEntity(ServerLevel level, BlockPos pos, String beSnbt) {
        if (beSnbt == null || beSnbt.isEmpty()) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;

        CompoundTag tag = fromSnbt(beSnbt);
        if (tag == null) return;
        // Ensure coords (some serializers rely on them)
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());

        invokeBlockEntityLoad(level, be, tag);
        be.setChanged();
    }

    /** Entity snapshot without hard dependency on exact save signature. */
    public static String writeEntity(ServerLevel level, Entity ent) {
        if (ent == null) return null;
        CompoundTag tag = new CompoundTag();
        // Entity#save is stable across many versions
        ent.save(tag);
        return toSnbt(tag);
    }

    public static Entity spawnEntityFromSnapshot(ServerLevel level, String entityTypeId, String entSnbt, double x, double y, double z) {
        if (entityTypeId == null || entSnbt == null) return null;
        ResourceLocation key = ResourceLocation.tryParse(entityTypeId);
        if (key == null) return null;
        EntityType<?> type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(key);
        if (type == null) return null;

        CompoundTag tag = fromSnbt(entSnbt);
        if (tag == null) return null;

        Entity ent = type.create(level);
        if (ent == null) return null;

        // Attempt to load. Different mappings exist; try common ones.
        try {
            Method m = Entity.class.getMethod("load", CompoundTag.class);
            m.invoke(ent, tag);
        } catch (Throwable ignored) {
            try {
                Method m = Entity.class.getMethod("load", CompoundTag.class, HolderLookup.Provider.class);
                m.invoke(ent, tag, level.registryAccess());
            } catch (Throwable ignored2) {
                // Best-effort; entity will spawn without full data.
            }
        }

        ent.moveTo(x, y, z, ent.getYRot(), ent.getXRot());
        level.addFreshEntity(ent);
        return ent;
    }

    public static String writeItemStack(ItemStack stack, HolderLookup.Provider provider) {
        if (stack == null || stack.isEmpty()) return null;
        CompoundTag tag = invokeItemStackSave(stack, provider);
        return toSnbt(tag);
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

    private static void invokeBlockEntityLoad(ServerLevel level, BlockEntity be, CompoundTag tag) {
        // Preferred: loadWithComponents(tag, provider)
        try {
            Method m = BlockEntity.class.getMethod("loadWithComponents", CompoundTag.class, HolderLookup.Provider.class);
            m.invoke(be, tag, level.registryAccess());
            return;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }

        // Alternative: load(tag, provider)
        try {
            Method m = BlockEntity.class.getMethod("load", CompoundTag.class, HolderLookup.Provider.class);
            m.invoke(be, tag, level.registryAccess());
            return;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }

        // Legacy: load(tag)
        try {
            Method m = BlockEntity.class.getMethod("load", CompoundTag.class);
            m.invoke(be, tag);
        } catch (Throwable ignored) {
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

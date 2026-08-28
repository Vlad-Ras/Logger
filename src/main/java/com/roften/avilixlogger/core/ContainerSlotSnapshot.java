package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.HashSet;

/**
 * Strict slot snapshots for containers.
 *
 * Goal:
 * - Work for vanilla + modded storages (Create etc.)
 * - Provide deterministic rollback by restoring slots 1:1
 */
public final class ContainerSlotSnapshot {

    private ContainerSlotSnapshot() {}

    /** Snapshot for a block storage at pos. Returns SNBT string or null if nothing could be snapshotted. */
    public static String snapshot(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return null;

        // 1) Prefer capability (modded storages)
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            String cap = snapshotFromCapability(level, pos, be);
            if (cap != null) return cap;
        }

        // 2) Vanilla/container fallback (supports chest, barrel, shulker, etc.)
        Container cont = containerForPos(level, pos);
        if (cont != null) {
            return NbtSerde.toSnbt(writeContainer(cont, level.registryAccess()));
        }

        return null;
    }

    /** Apply snapshot to block storage at pos. Returns true if applied successfully. */
    public static boolean apply(ServerLevel level, BlockPos pos, String snapshotSnbt) {
        if (level == null || pos == null) return false;
        if (snapshotSnbt == null || snapshotSnbt.isBlank()) return false;
        CompoundTag tag = NbtSerde.fromSnbt(snapshotSnbt);
        if (!isStructurallyValid(tag)) return false;

        // A capability may reject a write after several slots were already changed. Keep a
        // local backup and verify the resulting snapshot byte-for-byte at the NBT level.
        CompoundTag backup = NbtSerde.fromSnbt(snapshot(level, pos));
        if (!isStructurallyValid(backup)) return false;

        if (!applyUnchecked(level, pos, tag)) {
            applyUnchecked(level, pos, backup);
            return false;
        }
        CompoundTag actual = NbtSerde.fromSnbt(snapshot(level, pos));
        if (equivalent(tag, actual)) return true;

        applyUnchecked(level, pos, backup);
        return false;
    }

    /** Parse-only validation used before a rollback starts changing the world. */
    public static boolean isValid(String snapshotSnbt) {
        return isStructurallyValid(NbtSerde.fromSnbt(snapshotSnbt));
    }

    private static boolean applyUnchecked(ServerLevel level, BlockPos pos, CompoundTag tag) {

        // 1) Prefer modifiable capability
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            boolean ok = applyToCapability(level, pos, be, tag);
            if (ok) {
                be.setChanged();
                BlockState st = level.getBlockState(pos);
                level.sendBlockUpdated(pos, st, st, 3);
                return true;
            }
        }

        // 2) Container fallback (including double chest combined container)
        Container cont = containerForPos(level, pos);
        if (cont != null) {
            if (!readContainer(cont, level.registryAccess(), tag)) return false;
            if (cont instanceof BlockEntity be2) {
                be2.setChanged();
            } else {
                // best-effort notify
                BlockState st = level.getBlockState(pos);
                level.sendBlockUpdated(pos, st, st, 3);
            }
            return true;
        }
        return false;
    }

    private static boolean isStructurallyValid(CompoundTag tag) {
        if (tag == null || !tag.contains("Size", Tag.TAG_INT) || !tag.contains("Items", Tag.TAG_LIST)) return false;
        int size = tag.getInt("Size");
        if (size < 0) return false;
        HashSet<Integer> seen = new HashSet<>();
        ListTag items = tag.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag item = items.getCompound(i);
            if (!item.contains("Slot", Tag.TAG_ANY_NUMERIC)) return false;
            int slot = readSlot(item);
            if (slot < 0 || slot >= size || !seen.add(slot)) return false;
        }
        return true;
    }

    private static boolean equivalent(CompoundTag expected, CompoundTag actual) {
        if (!isStructurallyValid(expected) || !isStructurallyValid(actual)) return false;
        return normalizeSlots(expected).equals(normalizeSlots(actual));
    }

    private static CompoundTag normalizeSlots(CompoundTag source) {
        CompoundTag copy = source.copy();
        ListTag items = copy.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag item = items.getCompound(i);
            if (item.contains("Slot", Tag.TAG_ANY_NUMERIC)) item.putInt("Slot", readSlot(item));
        }
        return copy;
    }

    /** Old snapshots used a byte and therefore need unsigned decoding; new snapshots use an int. */
    public static int readSlot(CompoundTag item) {
        if (item == null || !item.contains("Slot", Tag.TAG_ANY_NUMERIC)) return -1;
        Tag raw = item.get("Slot");
        return raw != null && raw.getId() == Tag.TAG_BYTE ? (item.getByte("Slot") & 0xFF) : item.getInt("Slot");
    }

    // ---------------- internals ----------------

    private static String snapshotFromCapability(ServerLevel level, BlockPos pos, BlockEntity be) {
        try {
            // NeoForge 21.1+: BlockEntity no longer exposes getCapability().
            // Query it through the level using reflection so we stay compatible across minor versions.
            IItemHandler h = getItemHandlerCompat(level, pos, be, null);
            if (h == null) for (Direction d : Direction.values()) {
                h = getItemHandlerCompat(level, pos, be, d);
                if (h != null) break;
            }
            if (h == null) return null;

            CompoundTag out = new CompoundTag();
            int size = Math.max(0, h.getSlots());
            out.putInt("Size", size);
            ListTag items = new ListTag();
            for (int i = 0; i < size; i++) {
                ItemStack st = h.getStackInSlot(i);
                if (st == null || st.isEmpty()) continue;
                CompoundTag it = new CompoundTag();
                it.putInt("Slot", i);
                Tag saved = st.save(level.registryAccess());
                if (saved instanceof CompoundTag ct) it.merge(ct);
                items.add(it);
            }
            out.put("Items", items);
            return NbtSerde.toSnbt(out);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean applyToCapability(ServerLevel level, BlockPos pos, BlockEntity be, CompoundTag snapshot) {
        try {
            IItemHandler h = getItemHandlerCompat(level, pos, be, null);
            if (h == null) for (Direction d : Direction.values()) {
                h = getItemHandlerCompat(level, pos, be, d);
                if (h != null) break;
            }
            if (!(h instanceof IItemHandlerModifiable mh)) return false;

            int size = snapshot.getInt("Size");
            if (size != mh.getSlots()) return false;

            // clear
            for (int i = 0; i < size; i++) mh.setStackInSlot(i, ItemStack.EMPTY);

            ListTag items = snapshot.getList("Items", Tag.TAG_COMPOUND);
            for (int i = 0; i < items.size(); i++) {
                CompoundTag it = items.getCompound(i);
                int slot = readSlot(it);
                if (slot < 0 || slot >= size) return false;
                ItemStack st = ItemStack.parse(level.registryAccess(), it).orElse(ItemStack.EMPTY);
                if (st.isEmpty()) return false;
                mh.setStackInSlot(slot, st);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IItemHandler getItemHandlerCompat(ServerLevel level, BlockPos pos, BlockEntity be, Direction side) {
        try {
            Object cap = Capabilities.ItemHandler.BLOCK;
            BlockState state = level.getBlockState(pos);

            // Try the most common signatures first.
            for (java.lang.reflect.Method m : level.getClass().getMethods()) {
                if (!m.getName().equals("getCapability")) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    Object out = null;
                    if (p.length == 5) {
                        // (cap, pos, state, be, context)
                        out = m.invoke(level, cap, pos, state, be, side);
                    } else if (p.length == 4) {
                        // (cap, pos, state, context) OR (cap, pos, be, context)
                        if (p[2].isAssignableFrom(BlockState.class)) out = m.invoke(level, cap, pos, state, side);
                        else out = m.invoke(level, cap, pos, be, side);
                    } else if (p.length == 3) {
                        // (cap, pos, context)
                        out = m.invoke(level, cap, pos, side);
                    }
                    if (out instanceof IItemHandler ih) return ih;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Container containerForPos(ServerLevel level, BlockPos pos) {
        try {
            BlockState state = level.getBlockState(pos);

            // Double chest: combine both halves into one deterministic container (pos first, then other).
            if (state.getBlock() instanceof ChestBlock) {
                BlockEntity be0 = level.getBlockEntity(pos);
                if (!(be0 instanceof Container c0)) return null;
                BlockPos other = otherHalfChestPos(level, pos, state);
                if (other != null) {
                    BlockEntity be1 = level.getBlockEntity(other);
                    if (be1 instanceof Container c1) {
                        return new CompoundContainer(c0, c1);
                    }
                }
                return c0;
            }

            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container c) return c;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static BlockPos otherHalfChestPos(ServerLevel level, BlockPos pos, BlockState state) {
        try {
            if (!(state.getBlock() instanceof ChestBlock)) return null;
            if (!state.hasProperty(ChestBlock.TYPE) || !state.hasProperty(ChestBlock.FACING)) return null;
            var type = state.getValue(ChestBlock.TYPE);
            if (type == net.minecraft.world.level.block.state.properties.ChestType.SINGLE) return null;
            Direction facing = state.getValue(ChestBlock.FACING);
            Direction offsetDir = (type == net.minecraft.world.level.block.state.properties.ChestType.LEFT)
                    ? facing.getClockWise()
                    : facing.getCounterClockWise();
            BlockPos other = pos.relative(offsetDir);
            BlockState otherState = level.getBlockState(other);
            if (otherState.getBlock() instanceof ChestBlock) return other;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static CompoundTag writeContainer(Container c, net.minecraft.core.HolderLookup.Provider provider) {
        CompoundTag out = new CompoundTag();
        int size = Math.max(0, c.getContainerSize());
        out.putInt("Size", size);
        ListTag items = new ListTag();
        for (int i = 0; i < size; i++) {
            ItemStack st = c.getItem(i);
            if (st == null || st.isEmpty()) continue;
            CompoundTag it = new CompoundTag();
            it.putInt("Slot", i);
            Tag saved = st.save(provider);
            if (saved instanceof CompoundTag ct) it.merge(ct);
            items.add(it);
        }
        out.put("Items", items);
        return out;
    }

    private static boolean readContainer(Container c, net.minecraft.core.HolderLookup.Provider provider, CompoundTag snapshot) {
        int size = snapshot.getInt("Size");
        if (size != c.getContainerSize()) return false;

        for (int i = 0; i < size; i++) c.setItem(i, ItemStack.EMPTY);

        ListTag items = snapshot.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag it = items.getCompound(i);
            int slot = readSlot(it);
            if (slot < 0 || slot >= size) return false;
            ItemStack st = ItemStack.parse(provider, it).orElse(ItemStack.EMPTY);
            if (st.isEmpty()) return false;
            c.setItem(slot, st);
        }
        return true;
    }
}

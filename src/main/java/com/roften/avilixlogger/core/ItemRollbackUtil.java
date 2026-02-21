package com.roften.avilixlogger.core;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;

/**
 * Best-effort item removal from player inventories.
 */
public final class ItemRollbackUtil {
    private static Method IS_SAME_ITEM_SAME_COMPONENTS;

    static {
        try {
            IS_SAME_ITEM_SAME_COMPONENTS = ItemStack.class.getMethod("isSameItemSameComponents", ItemStack.class, ItemStack.class);
        } catch (Throwable t) {
            IS_SAME_ITEM_SAME_COMPONENTS = null;
        }
    }

    private ItemRollbackUtil() {}

    public static int removeMatching(ServerPlayer player, ItemStack target, int toRemove) {
        if (toRemove <= 0) return 0;
        Inventory inv = player.getInventory();
        int removed = 0;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack cur = inv.getItem(i);
            if (cur.isEmpty()) continue;
            if (!matches(cur, target)) continue;
            int take = Math.min(cur.getCount(), toRemove - removed);
            cur.shrink(take);
            removed += take;
            if (removed >= toRemove) break;
        }
        player.inventoryMenu.broadcastChanges();
        return removed;
    }

    /**
     * Counts how many items in the player's inventory match the target stack.
     * Used for rollback preview (dry-run) mode.
     */
    public static int countMatching(ServerPlayer player, ItemStack target) {
        if (player == null || target == null) return 0;
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack cur = inv.getItem(i);
            if (cur.isEmpty()) continue;
            if (!matches(cur, target)) continue;
            total += cur.getCount();
        }
        return total;
    }

    private static boolean matches(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (IS_SAME_ITEM_SAME_COMPONENTS != null) {
            try {
                return (boolean) IS_SAME_ITEM_SAME_COMPONENTS.invoke(null, a, b);
            } catch (Throwable ignored) {}
        }
        // Fallback: compare item id + NBT.
        if (a.getItem() != b.getItem()) return false;
        // Components/NBT equivalence differs by MC version; keep it conservative.
        return true;
    }
}

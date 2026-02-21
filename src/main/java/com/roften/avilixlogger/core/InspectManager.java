package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CoreProtect-like inspect mode.
 *
 * Server-side: /log i toggles mode. While enabled, right-click with the inspect tool
 * will show logs for the clicked block....
 */
public final class InspectManager {

    private static final Set<UUID> INSPECTING = ConcurrentHashMap.newKeySet();

    private InspectManager() {}

    public static boolean isInspecting(Player player) {
        return player != null && INSPECTING.contains(player.getUUID());
    }

    public static boolean toggle(Player player) {
        if (player == null) return false;
        UUID id = player.getUUID();
        if (INSPECTING.contains(id)) {
            INSPECTING.remove(id);
            return false;
        }
        INSPECTING.add(id);
        return true;
    }

    public static void set(Player player, boolean enabled) {
        if (player == null) return;
        if (enabled) INSPECTING.add(player.getUUID());
        else INSPECTING.remove(player.getUUID());
    }

    public static boolean isHoldingInspectTool(Player player) {
        if (player == null) return false;
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) return false;
        Item tool = resolveToolItem(player);
        return tool != null && stack.getItem() == tool;
    }

    /**
     * Tool item is configured via config string. Fallback is minecraft:stick.
     */
    private static Item resolveToolItem(Player player) {
        String id = null;
        if (player != null && player.level() instanceof ServerLevel sl) {
            id = LoggerServerData.get(sl).getInspectToolItemId();
        }
        if (id == null) id = LoggerConfig.inspectToolItemId();
        ResourceLocation rl = null;
        try {
            // 1.21+ exposes ResourceLocation.parse and tryParse across mappings.
            rl = ResourceLocation.tryParse(id);
            if (rl == null) rl = ResourceLocation.parse(id);
        } catch (Throwable ignored) {
        }
        if (rl == null) {
            try {
                rl = ResourceLocation.tryParse("minecraft:stick");
            } catch (Throwable ignored) {
                rl = null;
            }
        }
        if (rl == null) return null;
        return BuiltInRegistries.ITEM.get(rl);
    }

    // Backwards-compatible alias used by some handlers
    public static boolean isHoldingTool(Player player) {
        return isHoldingInspectTool(player);
    }
}

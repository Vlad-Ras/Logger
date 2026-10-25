package com.roften.avilixlogger.compat.curios;

import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.common.CuriosRegistry;

/** Establish native lazy attachment presence before an action's first snapshot, never by ticking it. */
public final class CuriosCaptureDefaults {
    private CuriosCaptureDefaults() {}
    public static void prepare(Player player) {
        // Existing equipment is never reset, resized, deserialized or re-equipped by Logger.
        if (player.hasData(CuriosRegistry.INVENTORY.get())) return;
        String version=ModList.get().getModContainerById("curios").orElseThrow().getModInfo().getVersion().toString();
        if (!version.equals("9.5.1+1.21.1")) throw new IllegalStateException("Unknown Curios lazy attachment contract");
        // Native capability lookup creates the same initially empty inventory used by Curios'
        // combat hooks. Its absence -> presence is initialization, not an equipment transaction.
        CuriosApi.getCuriosInventory(player);
    }
}

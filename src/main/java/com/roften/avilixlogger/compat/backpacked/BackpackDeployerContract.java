package com.roften.avilixlogger.compat.backpacked;

import com.mrcrayfish.backpacked.core.ModSyncedDataKeys;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;

/** Materialize known lazy defaults before taking a snapshot, never during an undo.
 * A new empty Framework holder is not a gameplay change. Progress changes still
 * invalidate the joint operation until a progress rollback contract is provided.
 * Loaded only behind the optional Backpacked guard.
 */
public final class BackpackDeployerContract {
    private BackpackDeployerContract() {}
    public static void prepare(Player player){
        var mods=ModList.get();
        if(!mods.getModContainerById("backpacked").orElseThrow().getModInfo().getVersion().toString().equals("3.0.5")
                ||!mods.getModContainerById("framework").orElseThrow().getModInfo().getVersion().toString().equals("0.13.10"))
            throw new IllegalStateException("Неизвестный контракт lazy defaults Backpacked/Framework");
        ModSyncedDataKeys.UNLOCK_TRACKER.getValue(player);
        ModSyncedDataKeys.BACKPACK.getValue(player);
        var backpacks=ModSyncedDataKeys.BACKPACKS.getValue(player);
        int capacity=com.mrcrayfish.backpacked.inventory.ManagementInventory.getMaxEquipable();
        // The supplier starts with an empty list; the native getter expands it to
        // the configured empty slots. Never invoke its resizing/drop path on items.
        if(!backpacks.isEmpty()&&backpacks.size()!=capacity)
            throw new IllegalStateException("Размер существующего инвентаря Backpacked требует миграции до снимка");
        com.mrcrayfish.backpacked.BackpackHelper.getBackpacks(player);
        var slots=ModSyncedDataKeys.UNLOCKABLE_BACKPACK_SLOTS.getValue(player);
        if(slots.getMaxSlots()!=0&&slots.getMaxSlots()!=capacity)
            throw new IllegalStateException("Существующие слоты Backpacked требуют миграции до снимка");
        com.mrcrayfish.backpacked.BackpackHelper.getBackpackUnlockableSlots(player);
        ModSyncedDataKeys.COSMETIC_PROPERTIES.getValue(player);
    }
}

package com.roften.avilixlogger.compat.chipped;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Chipped transforms the player's own inventory through a custom packet, not container slots. */
public final class ChippedCraftAudit {
    private ChippedCraftAudit() {}
    public static Action begin(ServerPlayer player, ItemStack requested, boolean all) {
        if (!CaptureTypePolicy.enabled(4) || player == null || !player.server.isSameThread()
                || !LoggerConfig.isEnabled() || CartAuditContext.restoring() || requested.isEmpty()) return null;
        try {
            var prototype=requested.copyWithCount(1);
            var before=EntityContainerSerde.snapshot(player.getInventory(),player.registryAccess());
            return before==null?null:new Action(player,prototype,all,before,count(player,prototype));
        } catch (RuntimeException failure) { AdaptiveLogDiagnostics.adapterFailure("chipped-craft-before"); return null; }
    }
    private static long count(ServerPlayer player,ItemStack prototype) {
        long count=0;var inventory=player.getInventory();
        for(int i=0;i<inventory.getContainerSize();i++) {
            var stack=inventory.getItem(i);
            if(ItemStack.isSameItemSameComponents(stack,prototype))count+=stack.getCount();
        }
        return count;
    }
    public record Action(ServerPlayer player,ItemStack prototype,boolean all,CompoundTag before,long previousCount) {
        public void finish() {
            try {
                long produced=count(player,prototype)-previousCount;
                if(produced<=0)return;
                var after=EntityContainerSerde.snapshot(player.getInventory(),player.registryAccess());
                var row=capture(player,before,after,prototype,produced,all);
                if(row!=null)LoggerRuntime.storage(player.serverLevel()).append(row);
            } catch(RuntimeException failure) { AdaptiveLogDiagnostics.adapterFailure("chipped-craft-after"); }
        }
    }
    /** Detached payload kernel; does not authorize or publish records. No universal inventory rollback. */
    public static LogEntry capture(ServerPlayer player,CompoundTag before,CompoundTag after,ItemStack output,long produced,boolean all) {
        if(!player.server.isSameThread() || before==null || after==null || before.equals(after) || produced<=0)return null;
        var item=NbtSerde.snapshotItemStack(output,player.registryAccess());if(item==null)return null;
        var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.ITEM_TRANSFORM;
        row.actorUuid=CauseContext.actorUuid(player);row.actorName=CauseContext.actorName(player);
        row.dim=player.serverLevel().dimension().location().toString();var pos=player.blockPosition();
        row.x=pos.getX();row.y=pos.getY();row.z=pos.getZ();row.source="mod:chipped:workbench";
        row.count=(int)Math.min(Integer.MAX_VALUE,produced);
        row.extra="chipped workbench; replaceAll="+all+"; produced="+produced+"; player inventory; audit only";
        row.deferSnapshot(LogEntry.SnapshotField.ITEM,item);
        row.deferSnapshot(LogEntry.SnapshotField.PLAYER_INV_BEFORE,before);
        row.deferSnapshot(LogEntry.SnapshotField.PLAYER_INV_AFTER,after);
        return row;
    }
}

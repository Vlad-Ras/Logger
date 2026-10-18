package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

/** Event-bound inventory snapshots around a tagged drop's native pickup, never a tick scan. */
public final class CartLootTransferAudit {
    private static final ThreadLocal<Attempt> CURRENT=new ThreadLocal<>();
    private CartLootTransferAudit(){}
    public static boolean capturing(ItemEntity drop){var attempt=CURRENT.get();return attempt!=null&&attempt.drop==drop;}
    public static Attempt begin(ItemEntity drop,net.minecraft.world.entity.player.Player player){
        if(CURRENT.get()!=null || !CreateCartAudit.enabled() || drop.isRemoved() || !(player instanceof ServerPlayer recipient)
                || !(drop.level() instanceof ServerLevel) || !drop.getPersistentData().hasUUID("AvilixCartDrop"))return null;
        var attempt=new Attempt(drop,recipient);CURRENT.set(attempt);return attempt;
    }
    public static void inventoryAdd(ItemEntity drop){var attempt=CURRENT.get();if(attempt!=null&&attempt.drop==drop&&attempt.scope==null){attempt.scope=new Scope(drop,attempt.player);if(!ItemStack.matches(attempt.original,attempt.scope.original))attempt.scope.complete=false;}}
    public static final class Attempt {
        private final ItemEntity drop;private final ServerPlayer player;private final ItemStack original;private Scope scope;
        private Attempt(ItemEntity drop,ServerPlayer player){this.drop=drop;this.player=player;original=drop.getItem().copy();}
        public void finish(boolean success){
            try{
                if(scope!=null)scope.finish(success);
                else if(drop.isRemoved()||!ItemStack.matches(original,drop.getItem())){
                    // A pickup pre-event changed/removed the drop without native inventory insertion.
                    var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.ITEM_PICKUP;
                    row.dim=drop.level().dimension().location().toString();row.entityUuid=drop.getUUID();row.actorUuid=player.getUUID();row.actorName=player.getScoreboardName();
                    row.source=CartAuditContext.prefix(drop.getPersistentData().getUUID("AvilixCartDrop"))+"loot_pickup:0";row.extra="pickup pre-event changed drop without a complete recipient snapshot";
                    LogIdGenerator.ensure(row);if(CreateCartAudit.enabled())LoggerRuntime.storage((ServerLevel)drop.level()).append(row);
                }
            }finally{CURRENT.remove();}
        }
    }
    public static final class Scope {
        private final ItemEntity drop;private final ServerPlayer player;private final UUID cart,id=UUID.randomUUID();
        private final ItemStack original;private final ItemStack[] inventory;private final BlockPos pos;
        private boolean complete=true,finished;
        private Scope(ItemEntity drop,ServerPlayer player){
            this.drop=drop;this.player=player;cart=drop.getPersistentData().getUUID("AvilixCartDrop");pos=drop.blockPosition().immutable();
            original=drop.getItem().copy();int size=player.getInventory().getContainerSize();complete=size==41;
            inventory=new ItemStack[Math.min(size,41)];for(int i=0;i<inventory.length;i++)inventory[i]=player.getInventory().getItem(i).copy();
        }
        public LogEntry finish(boolean success){
            if(finished)throw new IllegalStateException("Снимок подбора уже завершён");finished=true;
            // Vanilla restores the removed entity's stack count for statistics after discard.
            ItemStack remaining=drop.isRemoved()?ItemStack.EMPTY:drop.getItem().copy();
            CompoundTag before=envelope(),after=envelope();ListTag bs=new ListTag(),as=new ListTag();
            for(int i=0;i<inventory.length;i++){
                var now=player.getInventory().getItem(i);
                if(!ItemStack.matches(inventory[i],now)){bs.add(slot(i,inventory[i]));as.add(slot(i,now));}
            }
            if(ItemStack.matches(original,remaining)&&bs.isEmpty())return null;
            before.put("Slots",bs);after.put("Slots",as);before.put("Item",item(original));after.put("Item",item(remaining));
            int picked=original.getCount()-remaining.getCount();
            boolean valid=success&&complete&&!original.isEmpty()&&picked>0
                    &&(remaining.isEmpty()||ItemStack.isSameItemSameComponents(original,remaining))
                    &&inventory.length==player.getInventory().getContainerSize();
            before.putBoolean("Complete",valid);after.putBoolean("Complete",valid);
            LogEntry row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.ITEM_PICKUP;
            row.dim=drop.level().dimension().location().toString();row.x=pos.getX();row.y=pos.getY();row.z=pos.getZ();
            row.actorUuid=player.getUUID();row.actorName=player.getScoreboardName();row.entityUuid=drop.getUUID();row.entityType="minecraft:item";
            row.source=CartAuditContext.prefix(cart)+"loot_pickup:0";row.count=Math.max(0,picked);
            row.extra="linked pickup; transfer="+id+"; remaining="+remaining.getCount();
            row.deferSnapshot(LogEntry.SnapshotField.PLAYER_INV_BEFORE,before);row.deferSnapshot(LogEntry.SnapshotField.PLAYER_INV_AFTER,after);
            if(valid)row.deferSnapshot(LogEntry.SnapshotField.ITEM,item(original.copyWithCount(picked)));
            LogIdGenerator.ensure(row);
            if(CreateCartAudit.enabled())LoggerRuntime.storage((ServerLevel)drop.level()).append(row);
            return row;
        }
        private CompoundTag envelope(){var n=new CompoundTag();n.putInt("Format",1);n.putString("Kind","loot_items");n.putUUID("Transfer",id);n.putUUID("Player",player.getUUID());n.putUUID("Drop",drop.getUUID());n.putInt("Changes",0);return n;}
        private CompoundTag item(ItemStack stack){if(stack.isEmpty())return new CompoundTag();var n=NbtSerde.snapshotItemStack(stack,player.registryAccess());if(n==null){complete=false;return new CompoundTag();}return n;}
        private CompoundTag slot(int index,ItemStack stack){var n=new CompoundTag();n.putInt("Slot",index);if(!stack.isEmpty())n.put("Item",item(stack));return n;}
    }
}

package com.roften.avilixlogger.compat.backpacked;

import com.roften.avilixlogger.core.ActionType;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.level.ServerLevel;

public final class BackpackEvents {
    private BackpackEvents() {}
    public static void crafted(PlayerEvent.ItemCraftedEvent event){
        if(BackpackAudit.enabled()&&event.getEntity() instanceof ServerPlayer p&&BackpackAudit.isBag(event.getCrafting()))
            BackpackAudit.lifecycle(new BackpackAudit.Ref(event.getCrafting(),p.serverLevel(),p.blockPosition(),p,"inventory",-1,null),p,ActionType.BACKPACK_CREATE,"crafted");
    }
    public static void join(PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer p)BackpackAudit.trackPlayer(p,ActionType.BACKPACK_LOAD);}
    public static void leave(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer p)BackpackAudit.trackPlayer(p,ActionType.BACKPACK_UNLOAD);}
    public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event){if(event.getEntity() instanceof ServerPlayer p)BackpackAudit.trackPlayer(p,ActionType.BACKPACK_TRANSFER);}
    public static void death(LivingDeathEvent event){
        if(!BackpackAudit.enabled())return;
        if(event.getEntity() instanceof ServerPlayer p){
            BackpackAudit.trackPlayer(p,ActionType.BACKPACK_TRANSFER);
            for(int i=0;i<p.getInventory().getContainerSize();i++){var stack=p.getInventory().getItem(i);if(BackpackAudit.isBag(stack))BackpackAudit.lifecycle(new BackpackAudit.Ref(stack,p.serverLevel(),p.blockPosition(),p,"inventory",i,null),null,ActionType.BACKPACK_STATE,"player_death:"+event.getSource().getMsgId());}
            var bags=com.mrcrayfish.backpacked.BackpackHelper.getBackpacks(p);for(int i=0;i<bags.size();i++){var stack=bags.get(i);if(BackpackAudit.isBag(stack))BackpackAudit.lifecycle(new BackpackAudit.Ref(stack,p.serverLevel(),p.blockPosition(),p,"equipped",i,null),null,ActionType.BACKPACK_STATE,"player_death:"+event.getSource().getMsgId());}
        }
    }
    public static void tooltip(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent event){
        String id=com.roften.avilixlogger.core.BackpackIds.shortId(event.getItemStack());if(id.isBlank())return;
        var audit=com.roften.avilixlogger.core.BackpackIds.metadata(event.getItemStack());
        event.getToolTip().add(net.minecraft.network.chat.Component.literal("ID: "+id).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        String owner=audit.getString("OwnerName");if(!owner.isBlank())event.getToolTip().add(net.minecraft.network.chat.Component.literal("Владелец: "+owner).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }
    public static void itemJoin(EntityJoinLevelEvent event){
        if(BackpackAudit.enabled()&&event.getLevel() instanceof ServerLevel&&event.getEntity() instanceof ItemEntity item&&BackpackAudit.isBag(item.getItem()))
            BackpackAudit.track(BackpackAudit.itemRef(item),event.loadedFromDisk()?ActionType.BACKPACK_LOAD:ActionType.BACKPACK_DROP);
    }
    public static void itemLeave(EntityLeaveLevelEvent event){
        if(BackpackAudit.enabled()&&event.getLevel() instanceof ServerLevel&&event.getEntity() instanceof ItemEntity item&&BackpackAudit.isBag(item.getItem())){
            // A leave event alone does not prove loss: pickup, recall and chunk unload also remove entities.
            if(item.getRemovalReason()==net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK)
                BackpackAudit.lifecycle(BackpackAudit.itemRef(item),null,ActionType.BACKPACK_UNLOAD,"chunk_unload");
            else if(item.getRemovalReason()==net.minecraft.world.entity.Entity.RemovalReason.KILLED)
                BackpackAudit.lifecycle(BackpackAudit.itemRef(item),null,ActionType.BACKPACK_DESTROY,"entity_killed");
        }
    }
}

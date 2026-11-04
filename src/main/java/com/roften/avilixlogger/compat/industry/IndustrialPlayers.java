package com.roften.avilixlogger.compat.industry;

import com.roften.avilixlogger.compat.teams.ModStateAudit;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;

/** Actual native player/tool mutations; fake deployers are targets, never human actors. */
public final class IndustrialPlayers {
    private static final ThreadLocal<Integer> PLAYERS = ThreadLocal.withInitial(() -> 0);
    private IndustrialPlayers() {}
    public static Action begin(Object value,String provider,String operation,ItemStack focus,boolean experience) {
        if(!(value instanceof ServerPlayer player)||!player.server.isSameThread())return null;
        var type=experience?ActionType.EXPERIENCE_CHANGE:ActionType.ITEM_TRANSFORM;
        if(!ModStateAudit.enabled(player.server,type)||ModStateAudit.capturing(player,"industrial-player"))return null;
        IndustrialTools.player(player);if(focus!=null)IndustrialTools.release(focus);
        var stack=new StackRef(focus);
        var action=ModStateAudit.begin(player.server,provider,operation,player,"industrial-player",type,()->snapshot(player,stack));
        if(action==null)return null; PLAYERS.set(PLAYERS.get()+1);return new Action(action,stack);
    }
    private static CompoundTag snapshot(ServerPlayer player,StackRef stack) {
        var tag=new CompoundTag();var pos=player.blockPosition();
        tag.putString("Dimension",player.level().dimension().location().toString());tag.putInt("X",pos.getX());tag.putInt("Y",pos.getY());tag.putInt("Z",pos.getZ());
        tag.putUUID("EntityUUID",player.getUUID());tag.putString("EntityType","minecraft:player");tag.putString("EntityName",player.getName().getString());
        tag.putBoolean("FakePlayer",player instanceof net.neoforged.neoforge.common.util.FakePlayer);
        tag.putInt("ExperienceLevel",player.experienceLevel);tag.putInt("TotalExperience",player.totalExperience);tag.putFloat("ExperienceProgress",player.experienceProgress);
        tag.put("Inventory",EntityContainerSerde.snapshot(player.getInventory(),player.registryAccess()));
        tag.put("MainHand",player.getMainHandItem().saveOptional(player.registryAccess()));tag.put("OffHand",player.getOffhandItem().saveOptional(player.registryAccess()));
        tag.put("Cursor",player.containerMenu.getCarried().saveOptional(player.registryAccess()));
        var origin=IndustrialAudit.origin(); if(origin!=null)tag.put("Mechanism",com.roften.avilixlogger.compat.machines.MachineAudit.location(origin));
        if(stack.value!=null)tag.put("FocusStack",stack.value.saveOptional(player.registryAccess()));
        return tag;
    }
    public record Site(ServerLevel level,net.minecraft.core.BlockPos pos){}
    public static ModStateAudit.Action stack(ItemStack stack,Object owner){
        var server=net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if(server==null||!server.isSameThread()||!IndustrialAudit.supportsDiesel()||PLAYERS.get()>0||IndustrialTools.capturing()
                ||!net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("createdieselgenerators"))return null;
        IndustrialTools.release(stack);
        var origin=IndustrialAudit.origin();var cause=CauseContext.peek();
        Entity target=owner instanceof Entity entity?entity:null;
        var actor=cause!=null&&cause.actorUuid()!=null?server.getPlayerList().getPlayer(cause.actorUuid()):null;
        return ModStateAudit.begin(server,"createdieselgenerators","native-fluid-item-state",stack,"industrial-stack",ActionType.ITEM_TRANSFORM,()->{
            var tag=new CompoundTag();
            if(owner instanceof Site site){tag.putString("Dimension",site.level().dimension().location().toString());tag.putInt("X",site.pos().getX());tag.putInt("Y",site.pos().getY());tag.putInt("Z",site.pos().getZ());tag.putString("PositionMeaning","native-target-argument");}
            else if(target!=null){tag.putString("Dimension",target.level().dimension().location().toString());tag.putInt("X",target.blockPosition().getX());tag.putInt("Y",target.blockPosition().getY());tag.putInt("Z",target.blockPosition().getZ());tag.putUUID("EntityUUID",target.getUUID());tag.putString("EntityType",net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());tag.putString("EntityName",target.getName().getString());tag.putString("PositionMeaning","native-holder-argument");}
            else if(origin!=null)tag=com.roften.avilixlogger.compat.machines.MachineAudit.location(origin);
            else if(actor!=null){var pos=cause.pos()!=null?cause.pos():actor.blockPosition();tag.putString("Dimension",actor.level().dimension().location().toString());tag.putInt("X",pos.getX());tag.putInt("Y",pos.getY());tag.putInt("Z",pos.getZ());tag.putString("PositionMeaning","actor-context-not-proven-item-owner");}
            else tag.putString("Dimension","unknown");
            tag.putBoolean("HolderKnown",target!=null);
            tag.putBoolean("PositionKnown",target!=null||origin!=null||owner instanceof Site||actor!=null);tag.put("FocusStack",stack.saveOptional(server.registryAccess()));return tag;
        });
    }
    public static ModStateAudit.Action repair(ServerLevel level,ItemStack stack,boolean simulate) {
        if(simulate||level==null||!level.getServer().isSameThread()||PLAYERS.get()>0)return null;
        return ModStateAudit.begin(level.getServer(),"create_enchantment_industry","mending-item",stack,"repair",ActionType.ITEM_TRANSFORM,()->{
            var origin=IndustrialAudit.origin();
            CompoundTag tag=origin!=null?com.roften.avilixlogger.compat.machines.MachineAudit.location(origin):new CompoundTag();
            tag.putString("Dimension",level.dimension().location().toString());tag.putBoolean("PositionKnown",origin!=null);
            tag.put("FocusStack",stack.saveOptional(level.registryAccess()));return tag;
        });
    }
    public static final class Action {
        private final ModStateAudit.Action action;private final StackRef focus;
        private Action(ModStateAudit.Action action,StackRef focus){this.action=action;this.focus=focus;}
        public void result(Object result){
            if(result instanceof ItemStack stack)focus.value=stack;
            else if(result instanceof net.minecraft.world.InteractionResultHolder<?> holder&&holder.getObject() instanceof ItemStack stack)focus.value=stack;
        }
        public void finish(boolean threw){try{action.finish(threw);}finally{int count=PLAYERS.get()-1;if(count==0)PLAYERS.remove();else PLAYERS.set(count);}}
    }
    private static final class StackRef{private ItemStack value;private StackRef(ItemStack value){this.value=value;}}
}

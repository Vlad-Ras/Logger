package com.roften.avilixlogger.compat.dieselgenerators.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.*;
import com.roften.avilixlogger.compat.industry.*;

@Pseudo
@Mixin(targets = "com.jesz.createdieselgenerators.content.tools.lighter.LighterItem", remap = false)
public abstract class LighterMixin {
    @WrapMethod(method = "use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;", remap = false)
    private net.minecraft.world.InteractionResultHolder<ItemStack> avilixlogger$use(Level level, Player player, net.minecraft.world.InteractionHand hand, Operation<net.minecraft.world.InteractionResultHolder<ItemStack>> original){
        var action=IndustrialPlayers.begin(player,"createdieselgenerators","lighter-use",player.getItemInHand(hand),false); boolean success=false;
        try{var result=original.call(level, player, hand);if(action!=null)action.result(result);success=true;return result;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;", remap = false)
    private net.minecraft.world.InteractionResult avilixlogger$block(net.minecraft.world.item.context.UseOnContext context, Operation<net.minecraft.world.InteractionResult> original){
        var action=IndustrialPlayers.begin(context.getPlayer(),"createdieselgenerators","lighter-use-block",context.getItemInHand(),false); boolean success=false;
        try{var result=original.call(context);success=true;return result;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method="inventoryTick(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;IZ)V",remap=false)
    private void avilixlogger$automatic(ItemStack stack,Level level,net.minecraft.world.entity.Entity entity,int slot,boolean selected,Operation<Void> original){
        try(var cause=com.roften.avilixlogger.core.CauseContext.unattributed()){
            var action=IndustrialTools.automaticLighter(entity,stack);boolean success=false;
            try{original.call(stack,level,entity,slot,selected);success=true;}finally{if(action!=null)action.finish(!success);}
        }
    }
    @WrapMethod(method="onEntityItemUpdate(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/item/ItemEntity;)Z",remap=false)
    private boolean avilixlogger$dropped(ItemStack stack,net.minecraft.world.entity.item.ItemEntity entity,Operation<Boolean> original){
        try(var cause=com.roften.avilixlogger.core.CauseContext.unattributed()){
            var action=IndustrialTools.automaticLighter(entity,entity.getItem());boolean success=false;
            try{boolean result=original.call(stack,entity);success=true;return result;}finally{if(action!=null)action.finish(!success);}
        }
    }
}

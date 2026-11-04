package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.compat.create.CartAutomationAudit;
import com.roften.avilixlogger.core.CartRestoreLocks;
import com.simibubi.create.api.contraption.dispenser.DefaultMountedDispenseBehavior;
import com.simibubi.create.api.contraption.dispenser.MountedDispenseBehavior;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value=DefaultMountedDispenseBehavior.class,remap=false)
public abstract class MountedContainerTransferMixin {
    @WrapOperation(method="dispense",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/entity/HopperBlockEntity;addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;"),require=1)
    private ItemStack avilixlogger$transfer(Container from,Container to,ItemStack item,Direction side,Operation<ItemStack> original,
                                          ItemStack stack,MovementContext context,BlockPos pos){
        if(CartRestoreLocks.containerLocked(to))return item;
        var target=pos.relative(MountedDispenseBehavior.getClosestFacingDirection(MountedDispenseBehavior.getDispenserNormal(context)));
        if(CartRestoreLocks.blockLocked(context.world,target))return item;
        var capture=context.world instanceof ServerLevel level?CartAutomationAudit.container(to,level,target,side):null;
        boolean success=false;
        try{var result=original.call(from,to,item,side);success=true;return result;}
        finally{if(capture!=null)capture.finish(success);}
    }
}

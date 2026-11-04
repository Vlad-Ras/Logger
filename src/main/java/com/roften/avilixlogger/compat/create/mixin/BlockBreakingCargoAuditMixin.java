package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CreateCargoAudit;
import com.roften.avilixlogger.core.CartAuditContext;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;

/** Only verified block-breaking loot is internal; a tick context is not proof of ownership. */
@Mixin(value=BlockBreakingMovementBehaviour.class,remap=false)
public abstract class BlockBreakingCargoAuditMixin {
    @WrapMethod(method="throwEntity")
    private void avilixlogger$throwOrigin(MovementContext context, net.minecraft.world.entity.Entity target, Operation<Void> original) {
        original.call(context,target);
        com.roften.avilixlogger.core.CreateEntityOrigin.capture(target);
    }
    @WrapMethod(method="destroyBlock")
    private void avilixlogger$loot(MovementContext context,BlockPos pos,Operation<Void> original){
        var entity=context.contraption.entity;
        if(entity==null || !CreateCargoAudit.active(entity)){original.call(context,pos);return;}
        var previous=CartAuditContext.loot(com.roften.avilixlogger.compat.create.CreateContraptionSources.id(entity));
        try(var scope=CreateCargoAudit.operation(context,"block_break_loot",true)){original.call(context,pos);}
        finally{CartAuditContext.loot(previous);}
    }
}

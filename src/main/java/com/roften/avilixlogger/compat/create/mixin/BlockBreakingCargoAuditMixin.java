package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CartAuditAccess;
import com.roften.avilixlogger.compat.create.CreateCartAudit;
import com.roften.avilixlogger.core.CartAuditContext;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;

/** Only verified block-breaking loot is internal; a tick context is not proof of ownership. */
@Mixin(value=BlockBreakingMovementBehaviour.class,remap=false)
public abstract class BlockBreakingCargoAuditMixin {
    @WrapMethod(method="destroyBlock")
    private void avilixlogger$loot(MovementContext context,BlockPos pos,Operation<Void> original){
        if(!CreateCartAudit.enabled() || !(context.contraption instanceof CartAuditAccess audit)){original.call(context,pos);return;}
        var previous=CartAuditContext.loot(audit.avilixlogger$cartState().id);
        try{original.call(context,pos);}finally{CartAuditContext.loot(previous);}
    }
}

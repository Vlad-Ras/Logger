package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CartMenuTransferAudit;
import com.roften.avilixlogger.core.CauseContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Includes automatic menu closure; returning the cursor stack must join its transfer history. */
@Mixin(ServerPlayer.class)
public abstract class CartMenuCloseAuditMixin {
    @WrapMethod(method={"closeContainer","doCloseContainer"})
    private void avilixlogger$close(Operation<Void> original){
        var player=(ServerPlayer)(Object)this;
        if(!player.server.isSameThread() || !com.roften.avilixlogger.core.CreateSupport.available()){original.call();return;}
        var scope=CartMenuTransferAudit.beginClose(player);if(scope==null){original.call();return;}
        try(var cause=CauseContext.push(player,CauseContext.Kind.OTHER,player.blockPosition(),ItemStack.EMPTY)){
            boolean success=false;try{original.call();success=true;}finally{scope.finish(success,true);}
        }
    }
}

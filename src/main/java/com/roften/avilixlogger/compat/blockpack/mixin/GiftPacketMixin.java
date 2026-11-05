package com.roften.avilixlogger.compat.blockpack.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.blockpack.BlockPackGiftAudit;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets="com.blockfactory.blockpack.neoforge.BlockPackNeoForge",remap=false)
public abstract class GiftPacketMixin {
    @WrapMethod(method="lambda$registerPayloads$1(Lnet/neoforged/neoforge/network/handling/IPayloadContext;Lcom/blockfactory/blockpack/network/GiftPayloads$SetGiftEnabled;)V",remap=false)
    private static void avilixlogger$gift(IPayloadContext context,@Coerce Object packet,Operation<Void> original){
        if (!CaptureHandlers.BLOCKPACK_GIFTPACKETMIXIN_GIFT.enabled()) { original.call(context,packet); return; }
        var action=BlockPackGiftAudit.begin(context);
        try{original.call(context,packet);}finally{if(action!=null)action.close();}
    }
}

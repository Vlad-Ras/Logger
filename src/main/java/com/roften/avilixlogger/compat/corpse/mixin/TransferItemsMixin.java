package com.roften.avilixlogger.compat.corpse.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.corpse.CorpseAudit;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "de.maxhenkel.corpse.net.MessageTransferItems", remap = false)
public abstract class TransferItemsMixin {
    @WrapMethod(method = "executeServerSide(Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V", remap = false)
    private void avilixlogger$transfer(IPayloadContext context, Operation<Void> original) {
        var action = context.player() instanceof ServerPlayer player ? CorpseAudit.begin(player, "transfer-all") : null;
        boolean success = false;
        try { original.call(context); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
}

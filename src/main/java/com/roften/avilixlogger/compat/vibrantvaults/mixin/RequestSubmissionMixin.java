package com.roften.avilixlogger.compat.vibrantvaults.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.vibrantvaults.VibrantVaultAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
@Pseudo
@Mixin(targets = "com.simibubi.create.content.logistics.stockTicker.StockCheckingBlockEntity", remap = false)
public abstract class RequestSubmissionMixin {
    @WrapMethod(method = "broadcastPackageRequest(Lcom/simibubi/create/content/logistics/packagerLink/LogisticallyLinkedBehaviour$RequestType;Lcom/simibubi/create/content/logistics/stockTicker/PackageOrderWithCrafts;Lcom/simibubi/create/content/logistics/packager/IdentifiedInventory;Ljava/lang/String;)Z", remap = false)
    private boolean avilixlogger$submit(@Coerce Object type, @Coerce Object order, @Coerce Object ignored, String address, Operation<Boolean> original) {
        var submission = VibrantVaultAudit.request(this, order, address); boolean accepted = false, success = false;
        try { accepted = original.call(type, order, ignored, address); success = true; return accepted; }
        finally { if (submission != null) submission.finish(accepted, !success); }
    }
}

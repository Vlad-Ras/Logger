package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.api.inventories.InternalInventory;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.compat.ae2.Ae2RemoteMenuCapture;
import net.minecraft.server.level.ServerPlayer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import java.util.List;

/** This entry point does not exist in AE2 19.2.12; plugin enables it only for 19.2.18. */
@Mixin(targets="appeng.menu.implementations.PatternAccessTermMenu", remap=false)
public abstract class PatternAccessQuickMoveMixin {
    @WrapMethod(method="quickMovePattern")
    private void avilixlogger$remoteMove(ServerPlayer player, int slot, List<Long> allowed, Operation<Void> original) {
        var capture = (Ae2RemoteMenuCapture)this;
        boolean owner = capture.avilixlogger$remoteBefore(false);
        try { original.call(player, slot, allowed); } finally { capture.avilixlogger$remoteAfter(owner); }
    }
    @WrapOperation(method="quickMovePattern", at=@At(value="FIELD", target="Lappeng/menu/implementations/PatternAccessTermMenu$ContainerTracker;server:Lappeng/api/inventories/InternalInventory;", opcode=Opcodes.GETFIELD))
    private InternalInventory avilixlogger$quickInventory(@Coerce Object tracker, Operation<InternalInventory> original) {
        InternalInventory inventory = original.call(tracker);
        return ((Ae2RemoteMenuCapture)this).avilixlogger$observeRemote(tracker, inventory);
    }
}

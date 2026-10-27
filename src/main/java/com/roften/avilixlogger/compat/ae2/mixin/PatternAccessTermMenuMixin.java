package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.api.inventories.InternalInventory;
import appeng.helpers.InventoryAction;
import appeng.menu.AEBaseMenu;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.compat.ae2.Ae2RemoteAudit;
import net.minecraft.server.level.ServerPlayer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import java.util.List;

@Mixin(targets="appeng.menu.implementations.PatternAccessTermMenu", remap=false)
public abstract class PatternAccessTermMenuMixin {
    @Unique private Ae2RemoteAudit.Capture avilixlogger$remote;
    @WrapMethod(method="doAction")
    private void avilixlogger$remoteAction(ServerPlayer player, InventoryAction action, int slot, long id, Operation<Void> original) {
        boolean owner = avilixlogger$remote == null;
        if (owner) avilixlogger$remote = Ae2RemoteAudit.begin((AEBaseMenu)(Object)this, action == InventoryAction.MOVE_REGION);
        if (avilixlogger$remote != null && action == InventoryAction.MOVE_REGION) avilixlogger$remote.requireLiveReads();
        try { original.call(player, action, slot, id); } finally { if (owner) avilixlogger$closeRemote(); }
    }
    @WrapMethod(method="quickMovePattern")
    private void avilixlogger$remoteMove(ServerPlayer player, int slot, List<Long> allowed, Operation<Void> original) {
        boolean owner = avilixlogger$remote == null;
        if (owner) avilixlogger$remote = Ae2RemoteAudit.begin((AEBaseMenu)(Object)this, false);
        try { original.call(player, slot, allowed); } finally { if (owner) avilixlogger$closeRemote(); }
    }
    @WrapOperation(method={"doAction", "quickMovePattern"}, at=@At(value="FIELD", target="Lappeng/menu/implementations/PatternAccessTermMenu$ContainerTracker;server:Lappeng/api/inventories/InternalInventory;", opcode=Opcodes.GETFIELD))
    private InternalInventory avilixlogger$remoteInventory(@Coerce Object tracker, Operation<InternalInventory> original) {
        InternalInventory inventory = original.call(tracker);
        if (avilixlogger$remote == null) return inventory;
        try {
            var access = (PatternTrackerAccess)tracker;
            return avilixlogger$remote.wrap(inventory, access.avilixlogger$container(), access.avilixlogger$serverId());
        } catch (Throwable failure) { com.roften.avilixlogger.core.AdaptiveLogDiagnostics.adapterFailure("ae2-remote-pattern"); return inventory; }
    }
    @Unique private void avilixlogger$closeRemote() {
        var capture = avilixlogger$remote; avilixlogger$remote = null;
        if (capture != null) { try { capture.finish(); } catch (Throwable failure) { com.roften.avilixlogger.core.AdaptiveLogDiagnostics.adapterFailure("ae2-remote-pattern"); } }
    }
}

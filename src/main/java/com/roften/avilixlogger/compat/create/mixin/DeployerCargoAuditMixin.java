package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CreateCargoAudit;
import com.roften.avilixlogger.compat.create.CreateDeployerAudit;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.core.BlockPos;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.deployer.DeployerMovementBehaviour;
import org.spongepowered.asm.mixin.Mixin;

/** These transfers run outside activate(), so they need their own mechanism/owner scope. */
@Mixin(value = DeployerMovementBehaviour.class, remap = false)
public abstract class DeployerCargoAuditMixin {
    @Shadow private DeployerFakePlayer getPlayer(MovementContext context) { throw new AssertionError(); }
    @WrapMethod(method = "tryGrabbingItem")
    private void avilixlogger$take(MovementContext context, Operation<Void> original) {
        avilixlogger$exchange(context, "deployer_take", original);
    }
    @WrapMethod(method = "tryDisposeOfExcess")
    private void avilixlogger$return(MovementContext context, Operation<Void> original) {
        avilixlogger$exchange(context, "deployer_return", original);
    }
    @Unique private void avilixlogger$exchange(MovementContext context, String reason, Operation<Void> original) {
        if (!com.roften.avilixlogger.compat.create.CreateCartAudit.enabled()) {
            try (var scope = CreateCargoAudit.operation(context, reason, false)) { original.call(context); } return;
        }
        var target = context.position == null ? context.contraption.entity.blockPosition() : BlockPos.containing(context.position);
        CreateDeployerAudit.Session session;
        try { session = CreateDeployerAudit.capture(context, target, getPlayer(context), "USE"); }
        catch (Throwable failure) {
            com.roften.avilixlogger.AvilixLoggerMod.LOGGER.error("[AvilixLogger] Deployer inventory capture failed", failure);
            try { original.call(context); } finally { CreateDeployerAudit.captureFailed(context, target, "USE"); } return;
        }
        boolean completed = false;
        try (session; var scope = CreateCargoAudit.operation(context, reason, false)) {
            try { original.call(context); completed = true; }
            finally { if (session != null) try { CreateDeployerAudit.emit(session, completed); }
                catch (Throwable failure) { com.roften.avilixlogger.AvilixLoggerMod.LOGGER.error("[AvilixLogger] Deployer inventory result capture failed", failure); CreateDeployerAudit.captureFailed(context, target, "USE"); } }
        }
    }
}

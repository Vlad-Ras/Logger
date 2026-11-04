package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CreateDeployerAudit;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import com.simibubi.create.content.kinetics.deployer.DeployerMovementBehaviour;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(value = DeployerMovementBehaviour.class, remap = false)
public abstract class DeployerMovementAuditMixin {
    @WrapOperation(method = {"visitNewPosition", "tick"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/deployer/DeployerMovementBehaviour;activate(Lcom/simibubi/create/content/contraptions/behaviour/MovementContext;Lnet/minecraft/core/BlockPos;Lcom/simibubi/create/content/kinetics/deployer/DeployerFakePlayer;Lcom/simibubi/create/content/kinetics/deployer/DeployerBlockEntity$Mode;)V"), require = 1)
    private void avilixlogger$activate(DeployerMovementBehaviour actor, MovementContext context, BlockPos pos, DeployerFakePlayer player, @Coerce Object mode, Operation<Void> original) {
        if(com.roften.avilixlogger.core.CartRestoreLocks.handlerLocked(player.getAdvancements())||com.roften.avilixlogger.core.CartRestoreLocks.handlerLocked(player.getStats())||com.roften.avilixlogger.core.CartRestoreLocks.resourceLocked("create-score:"+player.getScoreboardName()))return;
        CreateDeployerAudit.Session session;
        try { session = CreateDeployerAudit.begin(context, pos, player, ((Enum<?>)mode).name()); }
        catch (Throwable failure) {
            com.roften.avilixlogger.AvilixLoggerMod.LOGGER.error("[AvilixLogger] Deployer capture failed", failure);
            try { original.call(actor, context, pos, player, mode); }
            finally { avilixlogger$incomplete(context, pos, mode); } return;
        }
        if (session == null) { original.call(actor, context, pos, player, mode); return; }
        boolean completed = false;
        try { original.call(actor, context, pos, player, mode); completed = true; }
        finally {
            try { CreateDeployerAudit.emit(session, completed); }
            catch (Throwable failure) { com.roften.avilixlogger.AvilixLoggerMod.LOGGER.error("[AvilixLogger] Deployer result capture failed", failure); avilixlogger$incomplete(context, pos, mode); }
            finally { session.close(); }
        }
    }
    @org.spongepowered.asm.mixin.Unique
    private static void avilixlogger$incomplete(MovementContext context, BlockPos pos, Object mode) {
        try { CreateDeployerAudit.captureFailed(context, pos, ((Enum<?>) mode).name()); }
        catch (Throwable failure) { com.roften.avilixlogger.AvilixLoggerMod.LOGGER.error("[AvilixLogger] Deployer incomplete history capture failed", failure); }
    }
}

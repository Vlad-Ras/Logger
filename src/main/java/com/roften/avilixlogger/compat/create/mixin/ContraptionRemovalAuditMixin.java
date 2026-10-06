package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CartAuditAccess;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Create removes the BE before setBlock; preserve its inventory at that exact boundary. */
@Mixin(value = Contraption.class, remap = false)
public abstract class ContraptionRemovalAuditMixin {
    @WrapOperation(method = "removeBlocksFromWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlockEntity(Lnet/minecraft/core/BlockPos;)V"), require = 1)
    private void avilixlogger$beforeRemove(Level world, BlockPos pos, Operation<Void> original) {
        if ((Object)this instanceof CartAuditAccess && CartAuditContext.current() != null && world instanceof ServerLevel level && !CartAuditContext.restoring()) {
            var state = level.getBlockState(pos); var be = level.getBlockEntity(pos);
            if (be != null) CartAuditContext.rememberRemovedBlock(pos, NbtSerde.snapshotBlockEntity(level, be), ContainerSlotSnapshot.snapshotTag(level, pos, state, be));
        }
        original.call(world, pos);
    }
}

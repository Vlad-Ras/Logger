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
    @WrapOperation(method = "addBlocksToWorld", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/contraptions/StructureTransform;apply(Lnet/minecraft/world/level/block/entity/BlockEntity;)V"), require = 1)
    private void avilixlogger$finalPlacedNbt(com.simibubi.create.content.contraptions.StructureTransform transform,net.minecraft.world.level.block.entity.BlockEntity be,Operation<Void> original) {
        var cause=CartAuditContext.current();
        if(!((Object)this instanceof CartAuditAccess) || cause==null || CartAuditContext.restoring() || !(be.getLevel() instanceof ServerLevel world)) {original.call(transform,be);return;}
        var cached=CartAuditContext.takePlacedBlock(be.getBlockPos());
        var beforeBe=cached==null?NbtSerde.snapshotBlockEntity(world,be):cached.be();
        var beforeSlots=cached==null?ContainerSlotSnapshot.snapshotTag(world,be.getBlockPos()):cached.slots();
        original.call(transform,be);
        var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.BLOCK_ENTITY_NBT_CHANGE;
        row.dim=world.dimension().location().toString();row.x=be.getBlockPos().getX();row.y=be.getBlockPos().getY();row.z=be.getBlockPos().getZ();row.source=cause.source();row.actorUuid=cause.owner();row.actorName=cause.ownerName();
        var playerCause=CauseContext.peek();if(playerCause!=null && playerCause.actorUuid()!=null){row.actorUuid=playerCause.actorUuid();row.actorName=playerCause.actorName();}
        var state=world.getBlockState(be.getBlockPos());row.deferBlockBefore(state);row.deferBlockAfter(state);
        row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,beforeBe);row.deferSnapshot(LogEntry.SnapshotField.SLOTS_BEFORE,beforeSlots);
        row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,NbtSerde.snapshotBlockEntity(world,be));row.deferSnapshot(LogEntry.SnapshotField.SLOTS_AFTER,ContainerSlotSnapshot.snapshotTag(world,be.getBlockPos()));
        row.extra="Create disassembly: final block entity and mounted inventory after unmount";LogIdGenerator.ensure(row);LoggerRuntime.storage(world).append(row);
    }
}

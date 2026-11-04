package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.roften.avilixlogger.compat.create.CreateTrainsCompatHooks;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value=StationBlockEntity.class, remap=false)
public abstract class StationBlockEntityMixin {
    @org.spongepowered.asm.mixin.injection.Inject(method="read",at=@At("HEAD"),require=1)
    private void avilixlogger$clearAbsentName(net.minecraft.nbt.CompoundTag tag,net.minecraft.core.HolderLookup.Provider registries,boolean clientPacket,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){if(com.roften.avilixlogger.core.CartAuditContext.restoring()&&!tag.contains("PrevTrainName",8))((StationBlockEntity)(Object)this).lastDisassembledTrainName=null;}
    @org.spongepowered.asm.mixin.injection.Inject(method={"assemble","dropSchedule","updateMapColor","cancelAssembly"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$voidLock(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){var self=(StationBlockEntity)(Object)this;if(com.roften.avilixlogger.core.CartRestoreLocks.blockLocked(self.getLevel(),self.getBlockPos()))ci.cancel();}
    @org.spongepowered.asm.mixin.injection.Inject(method={"enterAssemblyMode","exitAssemblyMode","tryDisassembleTrain","tryEnterAssemblyMode","updateName"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$booleanLock(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> ci){var self=(StationBlockEntity)(Object)this;if(com.roften.avilixlogger.core.CartRestoreLocks.blockLocked(self.getLevel(),self.getBlockPos()))ci.setReturnValue(false);}
    @WrapOperation(method="assemble", at=@At(value="INVOKE", target="Lcom/simibubi/create/content/trains/GlobalRailwayManager;addTrain(Lcom/simibubi/create/content/trains/entity/Train;)V"), require=1)
    private void avilixlogger$assembled(GlobalRailwayManager manager, Train train, Operation<Void> original) {
        original.call(manager, train);
        var station=(StationBlockEntity)(Object)this;
        if (station.getLevel() instanceof ServerLevel level)
            CreateTrainsCompatHooks.logTrainAssembled(level,train,station.getBlockPos());
    }
    @WrapOperation(method="tryDisassembleTrain", at=@At(value="INVOKE", target="Lcom/simibubi/create/content/trains/entity/Train;disassemble(Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;)Z"), require=1)
    private boolean avilixlogger$disassembled(Train train, Direction direction, BlockPos pos, Operation<Boolean> original, ServerPlayer sender) {
        boolean result;try(var cause=sender==null?com.roften.avilixlogger.core.CauseContext.unattributed():com.roften.avilixlogger.core.CauseContext.push(sender,com.roften.avilixlogger.core.CauseContext.Kind.OTHER,pos,sender.getMainHandItem())){result=original.call(train,direction,pos);}
        var station=(StationBlockEntity)(Object)this;
        if (result && station.getLevel() instanceof ServerLevel level)
            CreateTrainsCompatHooks.logTrainDisassembled(level,train,CreateTrainsCompatHooks.actorUuid(sender),CreateTrainsCompatHooks.actorName(sender),station.getBlockPos());
        return result;
    }
}

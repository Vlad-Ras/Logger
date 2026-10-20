package com.roften.avilixlogger.compat.create.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.*;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.core.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=Train.class,remap=false)
public abstract class TrainDisassemblyTransactionMixin {
    @WrapMethod(method="disassemble")
    private boolean avilixlogger$disassemble(Direction direction,BlockPos pos,Operation<Boolean> original){
        var self=(Train)(Object)this;if(CreateTrainRegistry.locked(self.id))return false;
        try(var scope=CreateStructuralAudit.beginTrain(self)){boolean success=original.call(direction,pos);if(success&&scope!=null)scope.success();return success;}
    }
    @Inject(method={"tick","earlyTick","detachFromTracks","reattachToTracks"},at=@At("HEAD"),cancellable=true)
    private void avilixlogger$locked(CallbackInfo ci){if(CreateTrainRegistry.locked(((Train)(Object)this).id))ci.cancel();}
}

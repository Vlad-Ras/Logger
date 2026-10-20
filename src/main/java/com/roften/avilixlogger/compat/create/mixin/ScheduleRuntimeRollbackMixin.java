package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.*;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=ScheduleRuntime.class,remap=false)
public abstract class ScheduleRuntimeRollbackMixin {
    @Unique private boolean avilixlogger$locked(){return CreateTrainRegistry.locked(((ScheduleRuntime)(Object)this).train.id);}
    @Inject(method={"setSchedule","discardSchedule","tick","tickConditions","destinationReached","transitInterrupted","startCooldown"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$mutation(CallbackInfo ci){if(avilixlogger$locked())ci.cancel();}
    @Inject(method="returnSchedule",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$return(CallbackInfoReturnable<ItemStack> ci){if(avilixlogger$locked())ci.setReturnValue(ItemStack.EMPTY);}
}

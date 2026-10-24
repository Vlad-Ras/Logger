package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.*;
import com.simibubi.create.content.trains.entity.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="com.simibubi.create.content.trains.entity.Carriage$DimensionalCarriageEntity",remap=false)
public abstract class CarriageRepresentationAuditMixin {
    @Shadow @Final Carriage this$0;
    @Inject(method="createEntity",at=@At("RETURN"),require=1)
    private void avilixlogger$created(Level level,boolean passengers,CallbackInfo ci){if(level instanceof ServerLevel server)CreateTrainLifecycleAudit.changed(this$0,server,"representation_loaded");}
    @Inject(method="removeAndSaveEntity",at=@At("RETURN"),require=1)
    private void avilixlogger$removed(CarriageContraptionEntity entity,boolean portal,CallbackInfo ci){if(entity.level() instanceof ServerLevel server)CreateTrainLifecycleAudit.changed(this$0,server,portal?"portal_departure":"representation_unloaded");}
}


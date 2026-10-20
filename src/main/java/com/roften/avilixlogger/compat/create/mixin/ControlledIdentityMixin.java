package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateControllerIdentity;
import com.simibubi.create.content.contraptions.*;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=ControlledContraptionEntity.class,remap=false)
public abstract class ControlledIdentityMixin {
    @Inject(method="create",at=@At("RETURN"),require=1)
    private static void avilixlogger$controller(Level world,IControlContraption controller,Contraption contraption,CallbackInfoReturnable<ControlledContraptionEntity> ci){
        CreateControllerIdentity.bind(world,controller,contraption);
    }
}

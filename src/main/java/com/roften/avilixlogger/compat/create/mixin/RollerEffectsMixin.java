package com.roften.avilixlogger.compat.create.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CreateActorEffectsAudit;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.actors.roller.RollerMovementBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value=RollerMovementBehaviour.class,remap=false)
public abstract class RollerEffectsMixin {
    @WrapMethod(method="triggerPaver")
    private void avilixlogger$effects(MovementContext context,BlockPos pos,Operation<Void> original){
        try(var scope=CreateActorEffectsAudit.begin(context,"roller_pave",true,false)){original.call(context,pos);if(scope!=null)scope.success();}
    }
}

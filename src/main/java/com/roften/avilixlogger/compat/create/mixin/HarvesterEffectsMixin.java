package com.roften.avilixlogger.compat.create.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CreateActorEffectsAudit;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.actors.harvester.HarvesterMovementBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value=HarvesterMovementBehaviour.class,remap=false)
public abstract class HarvesterEffectsMixin {
    @WrapMethod(method="visitNewPosition")
    private void avilixlogger$effects(MovementContext context,BlockPos pos,Operation<Void> original){
        try(var scope=CreateActorEffectsAudit.begin(context,"harvester_loot",true,false)){original.call(context,pos);if(scope!=null)scope.success();}
    }
}

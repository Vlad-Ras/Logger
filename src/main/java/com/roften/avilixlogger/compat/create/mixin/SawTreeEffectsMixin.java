package com.roften.avilixlogger.compat.create.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CreateActorEffectsAudit;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.saw.SawMovementBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value=SawMovementBehaviour.class,remap=false)
public abstract class SawTreeEffectsMixin {
    @WrapMethod(method="dropItemFromCutTree")
    private void avilixlogger$effects(MovementContext context,BlockPos pos,ItemStack stack,Operation<Void> original){
        try(var scope=CreateActorEffectsAudit.begin(context,"saw_tree_loot",true,false)){original.call(context,pos,stack);if(scope!=null)scope.success();}
    }
}

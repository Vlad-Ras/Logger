package com.roften.avilixlogger.compat.create.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CreateActorEffectsAudit;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.processing.basin.BasinMovementBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value=BasinMovementBehaviour.class,remap=false)
public abstract class BasinEffectsMixin {
    @WrapMethod(method="dump")
    private void avilixlogger$effects(MovementContext context,net.minecraft.world.phys.Vec3 facing,Operation<Void> original){
        try(var scope=CreateActorEffectsAudit.begin(context,"basin_dump",false,true)){original.call(context,facing);if(scope!=null)scope.success();}
    }
}

package com.roften.avilixlogger.compat.create.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.compat.create.CreateCargoAudit;
import com.simibubi.create.content.contraptions.actors.plough.PloughMovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(value=PloughMovementBehaviour.class,remap=false)
public abstract class PloughUseAuditMixin {
    @WrapOperation(method="visitNewPosition",at=@At(value="INVOKE",target="Lnet/minecraft/world/item/ItemStack;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"),require=1)
    private InteractionResult avilixlogger$till(ItemStack stack,UseOnContext use,Operation<InteractionResult> original,MovementContext context,BlockPos pos){
        // getPlayer has already created the technical player in temporaryData at this call site.
        try(var scope=CreateCargoAudit.operation(context,"plough_till",false)){return original.call(stack,use);}
    }
}

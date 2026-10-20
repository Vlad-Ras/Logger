package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.compat.create.CreateCargoAudit;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.behaviour.dispenser.DropperMovementBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value=DropperMovementBehaviour.class,remap=false)
public abstract class DispenserCargoAuditMixin {
    @WrapMethod(method="visitNewPosition")
    private void avilixlogger$dispense(MovementContext context,BlockPos pos,Operation<Void> original){
        try(var scope=CreateCargoAudit.operation(context,context.state.is(Blocks.DROPPER)?"dropper_dispense":"dispenser_dispense",false)){
            var transfer=com.roften.avilixlogger.compat.create.CartAutomationAudit.mounted(context,pos);boolean success=false;
            try{original.call(context,pos);success=true;}finally{if(transfer!=null)transfer.finish(success);}
        }
    }
    @WrapOperation(method="visitNewPosition",at=@At(value="INVOKE",target="Lcom/simibubi/create/content/contraptions/behaviour/dispenser/DropperMovementBehaviour;getSlot(Lcom/simibubi/create/api/contraption/storage/item/MountedItemStorage;Lnet/minecraft/util/RandomSource;Lnet/neoforged/neoforge/items/IItemHandler;)I"),require=1)
    private int avilixlogger$refill(MountedItemStorage storage,RandomSource random,IItemHandler cargo,Operation<Integer> original,MovementContext context,BlockPos pos){
        try(var scope=CreateCargoAudit.operation(context,"dispenser_refill",false)){return original.call(storage,random,cargo);}
    }
}

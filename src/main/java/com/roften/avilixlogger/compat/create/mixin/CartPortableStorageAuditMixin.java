package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.compat.create.*;
import com.simibubi.create.content.contraptions.*;
import com.simibubi.create.content.contraptions.actors.psi.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={PortableItemInterfaceBlockEntity.class,PortableFluidInterfaceBlockEntity.class},remap=false)
public abstract class CartPortableStorageAuditMixin {
    @Inject(method="startTransferringTo",at=@At("RETURN"))
    private void avilixlogger$connect(Contraption contraption,float distance,CallbackInfo ci){
        var be=(BlockEntity)(Object)this;if(!(be.getLevel() instanceof ServerLevel level)||!(contraption.entity instanceof OrientedContraptionEntity entity)||CreateCartAudit.state(entity)==null)return;
        Object handler=be instanceof PortableItemInterfaceBlockEntity?level.getCapability(Capabilities.ItemHandler.BLOCK,be.getBlockPos(),null):level.getCapability(Capabilities.FluidHandler.BLOCK,be.getBlockPos(),null);
        CartStorageAudit.alias(handler,entity);
    }
}

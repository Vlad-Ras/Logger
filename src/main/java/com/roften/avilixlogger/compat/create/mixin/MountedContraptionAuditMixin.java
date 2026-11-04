package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.compat.create.*;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MountedContraption.class, remap = false)
public abstract class MountedContraptionAuditMixin implements CartAuditAccess {
    @Unique private CartAuditState avilixlogger$state;
    @Override public CartAuditState avilixlogger$cartState() {
        if (avilixlogger$state == null) avilixlogger$state = new CartAuditState();
        return avilixlogger$state;
    }
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "writeNBT")
    private CompoundTag avilixlogger$metadata(HolderLookup.Provider registries, boolean packet, com.llamalad7.mixinextras.injector.wrapoperation.Operation<CompoundTag> original) {
        if (CreateCartAudit.metadataOnly()) { CompoundTag n = new CompoundTag(); n.putString("Type", "create:mounted"); n.put(CartAuditState.KEY, avilixlogger$cartState().write()); return n; }
        return original.call(registries, packet);
    }
    @Inject(method = "assemble", at = @At("RETURN"), require = 1)
    private void avilixlogger$assembled(Level world, net.minecraft.core.BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && !world.isClientSide && CreateCartAudit.assembling() != null) avilixlogger$state = CreateCartAudit.assembling();
    }
    @Inject(method = "writeNBT", at = @At("RETURN"), require = 1)
    private void avilixlogger$write(HolderLookup.Provider registries, boolean spawnPacket, CallbackInfoReturnable<CompoundTag> cir) {
        cir.getReturnValue().put(CartAuditState.KEY, avilixlogger$cartState().write());
    }
    @Inject(method = "readNBT", at = @At("RETURN"), require = 1)
    private void avilixlogger$read(Level world, CompoundTag tag, boolean spawnPacket, CallbackInfo ci) {
        if (tag.contains(CartAuditState.KEY)) avilixlogger$cartState().read(tag.getCompound(CartAuditState.KEY));
    }
}

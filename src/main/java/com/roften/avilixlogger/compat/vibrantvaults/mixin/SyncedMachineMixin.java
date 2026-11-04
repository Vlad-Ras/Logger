package com.roften.avilixlogger.compat.vibrantvaults.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import com.roften.avilixlogger.compat.vibrantvaults.VibrantVaultAudit;
@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.blockEntity.SyncedBlockEntity", remap = false)
public abstract class SyncedMachineMixin {
    @org.spongepowered.asm.mixin.injection.Inject(method = "sendData()V", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), remap = false)
    private void avilixlogger$observe(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) { VibrantVaultAudit.observed(this); }

}

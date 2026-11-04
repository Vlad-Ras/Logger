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
@Mixin(targets = "com.simibubi.create.foundation.blockEntity.SmartBlockEntity", remap = false)
public abstract class SmartMachineMixin implements com.roften.avilixlogger.compat.machines.MachineState {
    @Unique private CompoundTag avilixlogger$machineState;
    public CompoundTag avilixlogger$machineBaseline() { return avilixlogger$machineState; }
    public void avilixlogger$machineBaseline(CompoundTag value) { avilixlogger$machineState = value; }
    @org.spongepowered.asm.mixin.injection.Inject(method = "initialize()V", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), remap = false)
    private void avilixlogger$initialize(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) { VibrantVaultAudit.baseline(this); }
    @WrapMethod(method = "loadAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", remap = false)
    private void avilixlogger$read(CompoundTag tag, HolderLookup.Provider provider, Operation<Void> original) {
        var action = VibrantVaultAudit.begin(this, "live-nbt-read", true); boolean success = false;
        try { original.call(tag, provider); success = true; }
        finally { try { if (action != null) action.finish(!success); } finally { VibrantVaultAudit.baseline(this); } }
    }

}

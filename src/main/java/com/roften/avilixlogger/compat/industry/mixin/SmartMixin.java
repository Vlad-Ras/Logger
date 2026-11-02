package com.roften.avilixlogger.compat.industry.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.*;
import com.roften.avilixlogger.compat.industry.*;

@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.blockEntity.SmartBlockEntity", remap = false)
public abstract class SmartMixin implements IndustrialState {
    @Unique private final IndustrialAudit.Data avilixlogger$industryData = new IndustrialAudit.Data();
    public IndustrialAudit.Data avilixlogger$industrial(){return avilixlogger$industryData;}
    @Inject(method = "initialize()V", at = @At("HEAD"), remap = false)
    private void avilixlogger$loaded(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){IndustrialAudit.loaded(this);}
    @Inject(method = "tick()V", at = @At("RETURN"), remap = false)
    private void avilixlogger$due(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){IndustrialFluids.due(this);}
    @Inject(method = {"setRemoved()V", "onChunkUnloaded()V"}, at = @At("HEAD"), remap = false)
    private void avilixlogger$removed(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){IndustrialAudit.removed(this);}
    @WrapMethod(method = "loadAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", remap = false)
    private void avilixlogger$read(CompoundTag tag, HolderLookup.Provider provider, Operation<Void> original) {
        var action = IndustrialAudit.liveRead(this); boolean success = false;
        try { original.call(tag, provider); success = true; }
        finally { try {if(action!=null)action.finish(!success);} finally {IndustrialAudit.loaded(this);} }
    }
}

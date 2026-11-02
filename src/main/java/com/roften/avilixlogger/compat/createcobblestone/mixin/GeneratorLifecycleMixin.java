package com.roften.avilixlogger.compat.createcobblestone.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import com.roften.avilixlogger.compat.createcobblestone.CobblestoneAudit;
@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.blockEntity.SmartBlockEntity", remap = false)
public abstract class GeneratorLifecycleMixin {
    @org.spongepowered.asm.mixin.injection.Inject(method = {"setRemoved()V", "onChunkUnloaded()V"}, at = @org.spongepowered.asm.mixin.injection.At("HEAD"), remap = false)
    private void avilixlogger$flush(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (this.getClass().getName().equals("net.createcobblestone.neoforge.blocks.MechanicalGeneratorBlockEntity")) CobblestoneAudit.flush(this);
    }

}

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
@Mixin(targets = "com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour", remap = false)
public abstract class ValueMixin {
    @WrapMethod(method = "setValue(I)V", remap = false)
    private void avilixlogger$value(int value, Operation<Void> original) {
        var action = IndustrialAudit.behaviour(this,"value"); boolean success = false;
        try { original.call(value); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
}

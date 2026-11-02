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
@Mixin(targets = "net.minecraft.world.level.block.entity.BlockEntity", remap = false)
public abstract class AttachMixin {
    @WrapMethod(method = "setLevel(Lnet/minecraft/world/level/Level;)V", remap = false)
    private void avilixlogger$attach(Level level, Operation<Void> original) {
        original.call(level); IndustrialAudit.attached(this);
    }
}

package com.roften.avilixlogger.compat.vibrantvaults.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.vibrantvaults.VibrantVaultAudit;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value = BlockEntity.class, remap = false)
public abstract class AttachMachineMixin {
    @WrapMethod(method = "setLevel(Lnet/minecraft/world/level/Level;)V", remap = false)
    private void avilixlogger$attached(Level level, Operation<Void> original) {
        original.call(level);
        VibrantVaultAudit.attached(this);
    }
}

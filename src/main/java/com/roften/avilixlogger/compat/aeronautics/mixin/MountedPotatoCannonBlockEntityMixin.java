package com.roften.avilixlogger.compat.aeronautics.mixin;

import com.roften.avilixlogger.compat.aeronautics.AeronauticsCompatHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "dev.eriksonn.aeronautics.content.blocks.mounted_potato_cannon.MountedPotatoCannonBlockEntity")
public class MountedPotatoCannonBlockEntityMixin {
    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"),
            require = 0
    )
    private boolean avilixlogger$logMountedCannonProjectile(Level level, Entity projectile) {
        try {
            if (level != null && !level.isClientSide && (Object) this instanceof BlockEntity be) {
                return AeronauticsCompatHooks.logMountedCannonProjectile(be, projectile);
            }
        } catch (Throwable ignored) {}
        return level != null && level.addFreshEntity(projectile);
    }
}

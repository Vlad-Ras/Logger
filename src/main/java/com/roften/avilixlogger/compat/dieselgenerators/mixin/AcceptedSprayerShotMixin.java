package com.roften.avilixlogger.compat.dieselgenerators.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.compat.industry.IndustrialImpacts;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
@Pseudo
@Mixin(targets="com.jesz.createdieselgenerators.content.tools.ChemicalSprayerItem",remap=false)
public abstract class AcceptedSprayerShotMixin {
    @WrapOperation(method="onUseTick",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"),remap=false)
    private boolean avilixlogger$spawn(Level level,Entity entity,Operation<Boolean> original){
        boolean accepted=original.call(level,entity);IndustrialImpacts.spawned(entity,accepted);return accepted;
    }
}

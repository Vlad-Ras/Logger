package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.core.CreateOriginDataAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Entity.class)
public interface CreateOriginDataAccessor extends CreateOriginDataAccess {
    @Accessor("persistentData") CompoundTag avilixlogger$originData();
}

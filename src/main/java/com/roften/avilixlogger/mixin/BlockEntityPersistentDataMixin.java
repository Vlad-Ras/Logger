package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.BlockEntityPersistentDataAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.*;

@Mixin(BlockEntity.class)
public abstract class BlockEntityPersistentDataMixin implements BlockEntityPersistentDataAccess {
    @Shadow private CompoundTag customPersistentData;
    public void avilixlogger$restorePersistentData(CompoundTag data){customPersistentData=data;}
}

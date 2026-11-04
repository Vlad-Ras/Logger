package com.roften.avilixlogger.compat.create;
import net.minecraft.nbt.CompoundTag;
public interface CreateDeployerRuntimeAccess {
    CompoundTag avilixlogger$deployerRuntime();
    void avilixlogger$deployerRuntime(CompoundTag value);
    static void validatePhysical(CompoundTag tag){
        if(!tag.contains("Fire",3)||!tag.contains("Sprinting",1)||!tag.contains("PersistentData",10)||!tag.contains("Motion",9)||tag.getList("Motion",6).size()!=3)
            throw new IllegalStateException("Нет физического состояния технического игрока");
        for(var value:tag.getList("Motion",6))if(!Double.isFinite(((net.minecraft.nbt.DoubleTag)value).getAsDouble()))throw new IllegalStateException("Некорректная скорость технического игрока");
    }
}

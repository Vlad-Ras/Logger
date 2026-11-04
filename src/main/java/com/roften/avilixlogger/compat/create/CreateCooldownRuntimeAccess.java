package com.roften.avilixlogger.compat.create;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import java.util.HashSet;

public interface CreateCooldownRuntimeAccess {
    CompoundTag avilixlogger$cooldownRuntime();
    void avilixlogger$cooldownRuntime(CompoundTag tag);
    void avilixlogger$validateCooldownRuntime(CompoundTag tag);
    static void validate(CompoundTag tag){
        if(!tag.contains("Tick",3)||tag.getInt("Tick")<0||!(tag.get("Entries") instanceof ListTag entries)
                ||!entries.isEmpty()&&entries.getElementType()!=10||entries.size()>256)throw new IllegalStateException("Нет полного снимка cooldown активатора");
        var ids=new HashSet<String>();for(var raw:entries){var row=(CompoundTag)raw;
            if(ResourceLocation.tryParse(row.getString("Item"))==null||!ids.add(row.getString("Item"))||!row.contains("Start",3)||!row.contains("End",3)
                    ||row.getInt("Start")<0||row.getInt("End")<=row.getInt("Start")||row.getInt("End")<=tag.getInt("Tick"))throw new IllegalStateException("Некорректный cooldown активатора");}
    }
}

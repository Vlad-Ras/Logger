package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.compat.create.*;
import net.minecraft.nbt.*;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.*;
import java.util.*;

@Mixin(CombatTracker.class)
public abstract class CombatRuntimeMixin implements CreateCombatRuntimeAccess {
    @Shadow @Final private List<CombatEntry> entries;
    @Shadow private int lastDamageTime;
    @Shadow private int combatStartTime;
    @Shadow private int combatEndTime;
    @Shadow private boolean inCombat;
    @Shadow private boolean takingDamage;
    public CompoundTag avilixlogger$combatRuntime(LivingEntity actor){
        if(entries.size()>128)throw new IllegalStateException("История боя превышает 128 записей");
        var tag=new CompoundTag();tag.putInt("LastDamageTime",lastDamageTime);tag.putInt("Start",combatStartTime);tag.putInt("End",combatEndTime);
        tag.putBoolean("InCombat",inCombat);tag.putBoolean("TakingDamage",takingDamage);var list=new ListTag();
        for(var entry:entries){var value=new CompoundTag();value.put("Source",CreateLivingRuntime.damage(entry.source(),actor));value.putFloat("Damage",entry.damage());
            value.putFloat("FallDistance",entry.fallDistance());if(entry.fallLocation()!=null)value.putString("FallLocation",entry.fallLocation().id());list.add(value);}
        tag.put("Entries",list);return tag;
    }
    public void avilixlogger$combatRuntime(CompoundTag tag,LivingEntity actor){
        var decoded=new ArrayList<CombatEntry>();
        for(var raw:tag.getList("Entries",10)){var entry=(CompoundTag)raw;decoded.add(new CombatEntry(CreateLivingRuntime.damage(entry.getCompound("Source"),actor),entry.getFloat("Damage"),
                entry.contains("FallLocation",8)?new FallLocation(entry.getString("FallLocation")):null,entry.getFloat("FallDistance")));}
        entries.clear();entries.addAll(decoded);lastDamageTime=tag.getInt("LastDamageTime");combatStartTime=tag.getInt("Start");combatEndTime=tag.getInt("End");
        inCombat=tag.getBoolean("InCombat");takingDamage=tag.getBoolean("TakingDamage");
    }
}

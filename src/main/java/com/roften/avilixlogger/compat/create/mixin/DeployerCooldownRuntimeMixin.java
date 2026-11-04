package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.compat.create.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import org.spongepowered.asm.mixin.*;
import java.util.*;

@Mixin(ItemCooldowns.class)
public abstract class DeployerCooldownRuntimeMixin implements CreateCooldownRuntimeAccess {
    @Shadow @Final private Map<Item,?> cooldowns;
    @Shadow private int tickCount;
    public CompoundTag avilixlogger$cooldownRuntime(){
        if(cooldowns.size()>256)throw new IllegalStateException("Слишком много cooldown активатора");
        var tag=new CompoundTag();tag.putInt("Tick",tickCount);var list=new ListTag();
        cooldowns.entrySet().stream().sorted(Comparator.comparing(e->BuiltInRegistries.ITEM.getKey(e.getKey()).toString())).forEach(entry->{
            var value=(CreateCooldownEntryAccess)entry.getValue();var row=new CompoundTag();row.putString("Item",BuiltInRegistries.ITEM.getKey(entry.getKey()).toString());
            row.putInt("Start",value.avilixlogger$start());row.putInt("End",value.avilixlogger$end());list.add(row);});
        tag.put("Entries",list);return tag;
    }
    private Map<Item,int[]> avilixlogger$decode(CompoundTag tag){
        CreateCooldownRuntimeAccess.validate(tag);
        var decoded=new LinkedHashMap<Item,int[]>();
        for(var raw:tag.getList("Entries",10)){var row=(CompoundTag)raw;var key=ResourceLocation.parse(row.getString("Item"));
            if(!BuiltInRegistries.ITEM.containsKey(key))throw new IllegalStateException("Предмет cooldown больше не зарегистрирован");
            decoded.put(BuiltInRegistries.ITEM.get(key),new int[]{row.getInt("Start"),row.getInt("End")});}
        return decoded;
    }
    public void avilixlogger$validateCooldownRuntime(CompoundTag tag){avilixlogger$decode(tag);}
    public void avilixlogger$cooldownRuntime(CompoundTag tag){
        var decoded=avilixlogger$decode(tag);
        cooldowns.clear();tickCount=tag.getInt("Tick");var self=(ItemCooldowns)(Object)this;
        decoded.forEach((item,times)->{self.addCooldown(item,Math.max(1,times[1]-times[0]));((CreateCooldownEntryAccess)cooldowns.get(item)).avilixlogger$times(times[0],times[1]);});
    }
}

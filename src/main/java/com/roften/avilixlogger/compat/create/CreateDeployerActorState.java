package com.roften.avilixlogger.compat.create;
import com.roften.avilixlogger.core.NbtSerde;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.*;
/** Player state outside LivingEntity, without native Inventory.save's 99-item codec limit. */
final class CreateDeployerActorState {
    static CompoundTag capture(ServerPlayer p){
        var tag=new CompoundTag();tag.putInt("Selected",p.getInventory().selected);tag.putInt("Level",p.experienceLevel);tag.putInt("TotalXP",p.totalExperience);tag.putFloat("XP",p.experienceProgress);
        tag.putInt("Seed",p.getEnchantmentSeed());tag.putInt("Score",p.getScore());p.getAbilities().addSaveData(tag);
        var items=new ListTag();for(int slot=0;slot<p.getInventory().getContainerSize();slot++){var stack=p.getInventory().getItem(slot);if(stack.isEmpty())continue;
            var value=NbtSerde.snapshotItemStack(stack,p.registryAccess());if(value==null)throw new IllegalStateException("Нет снимка руки активатора");value.putInt("Slot",slot);items.add(value);}tag.put("Inventory",items);validate(tag);return tag;
    }
    static void validate(CompoundTag tag){
        if(tag.getInt("Selected")<0||tag.getInt("Selected")>8||!tag.contains("Selected",3)||!tag.contains("Level",3)||tag.getInt("Level")<0||!tag.contains("TotalXP",3)||tag.getInt("TotalXP")<0
            ||!tag.contains("XP",5)||!Float.isFinite(tag.getFloat("XP"))||tag.getFloat("XP")<0||tag.getFloat("XP")>1||!tag.contains("Seed",3)||!tag.contains("Score",3)||!tag.contains("abilities",10))throw new IllegalStateException("Неполные данные технического игрока");
        if(!(tag.get("Inventory") instanceof ListTag items)||items.size()>41||!items.isEmpty()&&items.getElementType()!=10)throw new IllegalStateException("Неполный инвентарь активатора");
        var slots=new HashSet<Integer>();for(var raw:items){var value=(CompoundTag)raw;if(!value.contains("Slot",3)||value.getInt("Slot")<0||value.getInt("Slot")>=41||!slots.add(value.getInt("Slot"))||value.getString("id").isBlank()||value.getInt("count")<=0)throw new IllegalStateException("Повреждён слот активатора");}
        var abilities=tag.getCompound("abilities");for(String key:List.of("invulnerable","flying","mayfly","instabuild","mayBuild"))if(!abilities.contains(key,1))throw new IllegalStateException("Неполные способности активатора");
        for(String key:List.of("flySpeed","walkSpeed"))if(!abilities.contains(key,5)||!Float.isFinite(abilities.getFloat(key))||abilities.getFloat(key)<0)throw new IllegalStateException("Повреждены способности активатора");
    }
    static void preflight(ServerPlayer p,CompoundTag tag){validate(tag);for(var raw:tag.getList("Inventory",10))read(p,(CompoundTag)raw);}
    private static ItemStack read(ServerPlayer p,CompoundTag value){var stack=NbtSerde.readItemStack(value,p.registryAccess());if(stack.isEmpty()||stack.getCount()!=value.getInt("count"))throw new IllegalStateException("Предмет активатора прочитан не полностью");return stack;}
    static void restore(ServerPlayer p,CompoundTag tag){
        preflight(p,tag);var inv=p.getInventory();inv.clearContent();for(var raw:tag.getList("Inventory",10)){var value=(CompoundTag)raw;inv.setItem(value.getInt("Slot"),read(p,value));}inv.selected=tag.getInt("Selected");
        p.experienceLevel=tag.getInt("Level");p.totalExperience=tag.getInt("TotalXP");p.experienceProgress=tag.getFloat("XP");((CreatePlayerLivingAccess)p).avilixlogger$enchantmentSeed(tag.getInt("Seed"));p.setScore(tag.getInt("Score"));p.getAbilities().loadSaveData(tag);
    }
}

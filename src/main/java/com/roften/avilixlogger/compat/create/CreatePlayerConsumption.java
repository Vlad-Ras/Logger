package com.roften.avilixlogger.compat.create;
import com.roften.avilixlogger.compat.create.mixin.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.advancements.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.*;
import java.util.*;
/** Complete bounded recipient state for native food/drink consumption, distinct from actor ownership. */
public final class CreatePlayerConsumption {
    private CreatePlayerConsumption(){}
    public static CompoundTag capture(ServerPlayer p){
        var tag=new CompoundTag();tag.putInt("Format",1);tag.putInt("Menu",p.containerMenu.containerId);tag.putInt("Selected",p.getInventory().selected);
        tag.putInt("Level",p.experienceLevel);tag.putInt("TotalXP",p.totalExperience);tag.putFloat("XP",p.experienceProgress);tag.put("Carried",item(p.containerMenu.getCarried(),p));
        var items=new ListTag();for(int i=0;i<p.getInventory().getContainerSize();i++)if(!p.getInventory().getItem(i).isEmpty()){
            var value=item(p.getInventory().getItem(i),p);value.putInt("Slot",i);items.add(value);}tag.put("Inventory",items);
        tag.put("Cooldowns",((CreateCooldownRuntimeAccess)p.getCooldowns()).avilixlogger$cooldownRuntime());
        var stats=new ListTag();((ConsumptionStatsAccessor)p.getStats()).avilixlogger$stats().object2IntEntrySet().stream().filter(e->e.getIntValue()!=0)
            .sorted(Comparator.comparing(e->e.getKey().toString())).forEach(e->{var value=new CompoundTag();value.putString("Type",BuiltInRegistries.STAT_TYPE.getKey(e.getKey().getType()).toString());
                value.putString("Key",statKey(e.getKey()));value.putInt("Value",e.getIntValue());stats.add(value);});tag.put("Stats",stats);
        var advancements=new ListTag();((ConsumptionAdvancementsAccessor)p.getAdvancements()).avilixlogger$progress().entrySet().stream().filter(e->e.getValue().hasProgress())
            .sorted(Comparator.comparing(e->e.getKey().id().toString())).forEach(e->{var value=new CompoundTag();value.putString("Id",e.getKey().id().toString());value.putBoolean("Done",e.getValue().isDone());
                value.put("Progress",AdvancementProgress.CODEC.encodeStart(NbtOps.INSTANCE,e.getValue()).getOrThrow());value.put("Rewards",AdvancementRewards.CODEC.encodeStart(NbtOps.INSTANCE,e.getKey().value().rewards()).getOrThrow());advancements.add(value);});tag.put("Advancements",advancements);
        var scores=new ListTag();p.getScoreboard().listPlayerScores(p).keySet().stream().sorted(Comparator.comparing(Objective::getName)).forEach(objective->{
            var info=p.getScoreboard().getPlayerScoreInfo(p,objective);if(!(info instanceof Score score))throw new IllegalStateException("Неизвестное хранилище scoreboard");
            var value=new CompoundTag();value.putString("Name",objective.getName());value.putString("Criterion",objective.getCriteria().getName());value.put("Data",score.write(p.registryAccess()));scores.add(value);});tag.put("Scores",scores);
        var runtime=(ConsumptionPlayerRuntimeAccessor)p;var levitation=new CompoundTag();levitation.putInt("Time",runtime.avilixlogger$levitationTime());
        var origin=runtime.avilixlogger$levitationPos();levitation.putBoolean("Present",origin!=null);if(origin!=null){levitation.putDouble("X",origin.x);levitation.putDouble("Y",origin.y);levitation.putDouble("Z",origin.z);}tag.put("Levitation",levitation);
        validate(tag);return tag;
    }
    private static CompoundTag item(ItemStack stack,ServerPlayer p){if(stack.isEmpty())return new CompoundTag();var tag=NbtSerde.snapshotItemStack(stack,p.registryAccess());if(tag==null)throw new IllegalStateException("Нет снимка предмета получателя");return tag;}
    @SuppressWarnings({"rawtypes","unchecked"}) private static String statKey(Stat<?> stat){return ((StatType)stat.getType()).getRegistry().getKey(stat.getValue()).toString();}
    @SuppressWarnings({"rawtypes","unchecked"}) private static Stat<?> stat(CompoundTag value){
        var typeId=ResourceLocation.parse(value.getString("Type"));if(!BuiltInRegistries.STAT_TYPE.containsKey(typeId))throw new IllegalStateException("Неизвестный тип статистики");
        StatType type=BuiltInRegistries.STAT_TYPE.get(typeId);var key=ResourceLocation.parse(value.getString("Key"));if(!type.getRegistry().containsKey(key))throw new IllegalStateException("Неизвестная статистика");return type.get(type.getRegistry().get(key));
    }
    private static ListTag list(CompoundTag tag,String key,int max){if(!(tag.get(key) instanceof ListTag list)||list.size()>max||!list.isEmpty()&&list.getElementType()!=10)throw new IllegalStateException("Неполный список получателя: "+key);return list;}
    public static void validate(CompoundTag tag){
        if(tag.getInt("Format")!=1||tag.sizeInBytes()>2*1024*1024||!tag.contains("Menu",3)||!tag.contains("Selected",3)||tag.getInt("Selected")<0||tag.getInt("Selected")>8
                ||!tag.contains("Level",3)||tag.getInt("Level")<0||!tag.contains("TotalXP",3)||tag.getInt("TotalXP")<0||!tag.contains("XP",5)||!Float.isFinite(tag.getFloat("XP"))
                ||tag.getFloat("XP")<0||tag.getFloat("XP")>1||!tag.contains("Carried",10))throw new IllegalStateException("Неполное состояние получателя пищи");
        var slots=new HashSet<Integer>();for(var raw:list(tag,"Inventory",41)){var value=(CompoundTag)raw;int slot=value.getInt("Slot");if(!value.contains("Slot",3)||slot<0||slot>=41||!slots.add(slot)||value.getString("id").isBlank()||value.getInt("count")<=0)throw new IllegalStateException("Повреждён инвентарь получателя");}
        var ids=new HashSet<String>();for(var raw:list(tag,"Stats",4096)){var value=(CompoundTag)raw;if(ResourceLocation.tryParse(value.getString("Type"))==null||ResourceLocation.tryParse(value.getString("Key"))==null
                ||!ids.add(value.getString("Type")+"/"+value.getString("Key"))||!value.contains("Value",3)||value.getInt("Value")<0)throw new IllegalStateException("Повреждена статистика");}
        ids.clear();for(var raw:list(tag,"Advancements",4096)){var value=(CompoundTag)raw;if(ResourceLocation.tryParse(value.getString("Id"))==null||!ids.add(value.getString("Id"))||!value.contains("Done",1)||!value.contains("Progress",10)||!value.contains("Rewards",10))throw new IllegalStateException("Повреждено достижение");AdvancementProgress.CODEC.parse(NbtOps.INSTANCE,value.get("Progress")).getOrThrow();AdvancementRewards.CODEC.parse(NbtOps.INSTANCE,value.get("Rewards")).getOrThrow();}
        ids.clear();for(var raw:list(tag,"Scores",1024)){var value=(CompoundTag)raw;if(value.getString("Name").isBlank()||!ids.add(value.getString("Name"))||value.getString("Criterion").isBlank()||!value.getCompound("Data").contains("Score",3)||!value.getCompound("Data").contains("Locked",1))throw new IllegalStateException("Повреждён scoreboard");}
        var levitation=tag.getCompound("Levitation");if(!tag.contains("Levitation",10)||!levitation.contains("Time",3)||levitation.getInt("Time")<0||!levitation.contains("Present",1))throw new IllegalStateException("Нет состояния левитации получателя");
        if(levitation.getBoolean("Present"))for(String field:List.of("X","Y","Z"))if(!levitation.contains(field,6)||!Double.isFinite(levitation.getDouble(field)))throw new IllegalStateException("Повреждена точка левитации");
        CreateCooldownRuntimeAccess.validate(tag.getCompound("Cooldowns"));
    }
    public static boolean rewardsUnchanged(CompoundTag before,CompoundTag after){
        var done=new HashSet<String>();for(var raw:before.getList("Advancements",10)){var value=(CompoundTag)raw;if(value.getBoolean("Done"))done.add(value.getString("Id"));}
        for(var raw:after.getList("Advancements",10)){var value=(CompoundTag)raw;if(value.getBoolean("Done")&&!done.contains(value.getString("Id"))&&(!value.contains("Rewards",10)||!AdvancementRewards.CODEC.parse(NbtOps.INSTANCE,value.get("Rewards")).getOrThrow().equals(AdvancementRewards.EMPTY)))return false;}return true;
    }
    public static void preflight(ServerPlayer p,CompoundTag tag){
        validate(tag);for(var raw:tag.getList("Inventory",10))read((CompoundTag)raw,p);read(tag.getCompound("Carried"),p);
        for(var raw:tag.getList("Stats",10))stat((CompoundTag)raw);
        for(var raw:tag.getList("Advancements",10)){var value=(CompoundTag)raw;var holder=p.server.getAdvancements().get(ResourceLocation.parse(value.getString("Id")));if(holder==null)throw new IllegalStateException("Достижение удалено");if(!AdvancementRewards.CODEC.encodeStart(NbtOps.INSTANCE,holder.value().rewards()).getOrThrow().equals(value.get("Rewards")))throw new IllegalStateException("Награда достижения изменилась");
            var progress=AdvancementProgress.CODEC.parse(NbtOps.INSTANCE,value.get("Progress")).getOrThrow();progress.update(holder.value().requirements());if(progress.isDone()!=value.getBoolean("Done"))throw new IllegalStateException("Условия достижения изменились");}
        for(var raw:tag.getList("Scores",10)){var value=(CompoundTag)raw;var objective=p.getScoreboard().getObjective(value.getString("Name"));if(objective==null||!objective.getCriteria().getName().equals(value.getString("Criterion")))throw new IllegalStateException("Задача scoreboard заменена");
            if(!Score.read(value.getCompound("Data"),p.registryAccess()).write(p.registryAccess()).equals(value.getCompound("Data")))throw new IllegalStateException("Scoreboard прочитан не полностью");}
        ((CreateCooldownRuntimeAccess)p.getCooldowns()).avilixlogger$validateCooldownRuntime(tag.getCompound("Cooldowns"));
    }
    private static ItemStack read(CompoundTag tag,ServerPlayer p){var stack=NbtSerde.readItemStack(tag,p.registryAccess());if(!tag.isEmpty()&&(stack.isEmpty()||stack.getCount()!=tag.getInt("count")))throw new IllegalStateException("Предмет получателя прочитан не полностью");return stack;}
    public static void restore(ServerPlayer p,CompoundTag tag){
        preflight(p,tag);var inv=p.getInventory();inv.clearContent();inv.selected=tag.getInt("Selected");for(var raw:tag.getList("Inventory",10)){var value=(CompoundTag)raw;inv.setItem(value.getInt("Slot"),read(value,p));}
        p.containerMenu.setCarried(read(tag.getCompound("Carried"),p));p.experienceLevel=tag.getInt("Level");p.totalExperience=tag.getInt("TotalXP");p.experienceProgress=tag.getFloat("XP");
        ((CreateCooldownRuntimeAccess)p.getCooldowns()).avilixlogger$cooldownRuntime(tag.getCompound("Cooldowns"));
        var stats=new HashMap<Stat<?>,Integer>();for(var raw:tag.getList("Stats",10)){var value=(CompoundTag)raw;stats.put(stat(value),value.getInt("Value"));}
        var keys=new HashSet<>(((ConsumptionStatsAccessor)p.getStats()).avilixlogger$stats().keySet());keys.addAll(stats.keySet());for(var key:keys)p.getStats().setValue(p,key,stats.getOrDefault(key,0));
        var scores=p.getScoreboard();var names=new HashSet<String>();for(var raw:tag.getList("Scores",10)){var value=(CompoundTag)raw;var objective=scores.getObjective(value.getString("Name"));names.add(objective.getName());
            var data=Score.read(value.getCompound("Data"),p.registryAccess());var target=scores.getOrCreatePlayerScore(p,objective,true);target.set(data.value());target.display(data.display());target.numberFormatOverride(data.numberFormat());if(data.isLocked())target.lock();else target.unlock();}
        for(var objective:new ArrayList<>(scores.listPlayerScores(p).keySet()))if(!names.contains(objective.getName()))scores.resetSinglePlayerScore(p,objective);
        var access=(ConsumptionAdvancementsAccessor)p.getAdvancements();var progress=new HashMap<AdvancementHolder,AdvancementProgress>();for(var raw:tag.getList("Advancements",10)){var value=(CompoundTag)raw;var holder=p.server.getAdvancements().get(ResourceLocation.parse(value.getString("Id")));
            var decoded=AdvancementProgress.CODEC.parse(NbtOps.INSTANCE,value.get("Progress")).getOrThrow();decoded.update(holder.value().requirements());progress.put(holder,decoded);}
        var holders=new HashSet<>(access.avilixlogger$progress().keySet());holders.addAll(progress.keySet());p.getAdvancements().stopListening();
        for(var holder:holders){var value=progress.get(holder);if(value==null){value=new AdvancementProgress();value.update(holder.value().requirements());}
            access.avilixlogger$progress().put(holder,value);access.avilixlogger$changed().add(holder);access.avilixlogger$visibility(holder);access.avilixlogger$listen(holder);}
        var runtime=(ConsumptionPlayerRuntimeAccessor)p;var levitation=tag.getCompound("Levitation");runtime.avilixlogger$levitationTime(levitation.getInt("Time"));runtime.avilixlogger$levitationPos(levitation.getBoolean("Present")?new net.minecraft.world.phys.Vec3(levitation.getDouble("X"),levitation.getDouble("Y"),levitation.getDouble("Z")):null);
        reserve(p);inv.setChanged();p.containerMenu.broadcastFullState();p.getAdvancements().flushDirty(p);p.getStats().sendStats(p);
        if(p.connection!=null){p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHealthPacket(p.getHealth(),p.getFoodData().getFoodLevel(),p.getFoodData().getSaturationLevel()));p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetExperiencePacket(p.experienceProgress,p.totalExperience,p.experienceLevel));}
    }
    public static void reserve(ServerPlayer p){for(var objective:p.getScoreboard().getObjectives()){CartRestoreLocks.handler(objective);var score=p.getScoreboard().getPlayerScoreInfo(p,objective);if(score!=null)CartRestoreLocks.handler(score);}for(var progress:((ConsumptionAdvancementsAccessor)p.getAdvancements()).avilixlogger$progress().values())CartRestoreLocks.handler(progress);}
}

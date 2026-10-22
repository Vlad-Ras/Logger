package com.roften.avilixlogger.compat.create;
import com.roften.avilixlogger.compat.create.mixin.*;
import net.minecraft.advancements.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.*;
import java.util.*;
/** Exact criteria timestamps and raw counters. Restoring never grants a reward. */
public final class CreatePlayerProgress {
    private CreatePlayerProgress(){}
    // Avoid re-encoding every advancement definition for every Deployer/player snapshot.
    // The weak owner is the live reloadable advancement manager, not a permanent server reference.
    // Each recorded snapshot still gets its own detached NBT copy.
    private static final long MAX_DEFINITION_BYTES = 4L * 1024 * 1024;
    private static final int MAX_DEFINITIONS = 256;
    private static final Map<Object, DefinitionCache> DEFINITIONS = new WeakHashMap<>();
    private static final class DefinitionCache {
        final LinkedHashMap<AdvancementHolder, Tag> values = new LinkedHashMap<>(32, .75f, true);
        long bytes;
    }
    public static void clearDefinitionCache() {
        synchronized (DEFINITIONS) { DEFINITIONS.clear(); }
    }
    private static Tag definition(ServerPlayer player, AdvancementHolder holder) {
        synchronized (DEFINITIONS) {
            var owner = player.server.getAdvancements();
            var cache = DEFINITIONS.computeIfAbsent(owner, ignored -> new DefinitionCache());
            var previous = cache.values.get(holder);
            if (previous != null) return previous.copy();
            var encoded = Advancement.CODEC.encodeStart(
                    player.registryAccess().createSerializationContext(NbtOps.INSTANCE), holder.value()).getOrThrow();
            long bytes = encoded.sizeInBytes();
            if (bytes <= MAX_DEFINITION_BYTES) {
                cache.values.put(holder, encoded.copy());
                cache.bytes += bytes;
                while (cache.values.size() > MAX_DEFINITIONS || cache.bytes > MAX_DEFINITION_BYTES) {
                    var oldest = cache.values.entrySet().iterator();
                    var removed = oldest.next();
                    cache.bytes -= removed.getValue().sizeInBytes();
                    oldest.remove();
                }
            }
            return encoded;
        }
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private static String value(Stat<?> stat){return ((net.minecraft.core.Registry)stat.getType().getRegistry()).getKey(stat.getValue()).toString();}
    public static CompoundTag capture(ServerPlayer player){
        var out=new CompoundTag();out.putInt("Format",1);var stats=new ListTag();
        var entries=((PlayerStatsAccessor)player.getStats()).avilixlogger$stats().object2IntEntrySet().stream().sorted(Comparator.comparing(e->e.getKey().toString())).toList();
        for(var entry:entries){var row=new CompoundTag();var stat=entry.getKey();row.putString("Type",BuiltInRegistries.STAT_TYPE.getKey(stat.getType()).toString());row.putString("Value",value(stat));row.putInt("Count",entry.getIntValue());stats.add(row);}
        out.put("Stats",stats);var advancements=new ListTag();
        var progress=((PlayerProgressAccessor)player.getAdvancements()).avilixlogger$progress();
        for(var holder:progress.keySet().stream().sorted(Comparator.comparing(h->h.id().toString())).toList()){
            var state=progress.get(holder);if(!state.hasProgress())continue;var row=new CompoundTag();row.putString("Id",holder.id().toString());
            row.put("Definition",definition(player,holder));
            var criteria=new ListTag();
            for(String key:holder.value().criteria().keySet().stream().sorted().toList()){
                var criterion=state.getCriterion(key);if(criterion==null||!criterion.isDone())continue;var time=criterion.getObtained();var c=new CompoundTag();c.putString("Key",key);c.putLong("Second",time.getEpochSecond());c.putInt("Nano",time.getNano());criteria.add(c);
            }
            row.put("Criteria",criteria);advancements.add(row);
        }
        out.put("Advancements",advancements);out.put("Recipes",player.getRecipeBook().toNbt());validate(out);return out;
    }
    public static ListTag rows(CompoundTag tag,String key,int max){
        if(!(tag.get(key) instanceof ListTag list)||!list.isEmpty()&&list.getElementType()!=10||list.size()>max)throw new IllegalStateException("Некорректный список снимка: "+key);return list;
    }
    public static void validate(CompoundTag tag){
        if(tag.getInt("Format")!=1||!tag.contains("Stats",9)||!tag.contains("Advancements",9)||!tag.contains("Recipes",10)||tag.sizeInBytes()>2*1024*1024||tag.getList("Stats",10).size()>8192||tag.getList("Advancements",10).size()>2048)throw new IllegalStateException("Неполная статистика или достижения получателя");
        rows(tag,"Stats",8192);rows(tag,"Advancements",2048);
        var keys=new HashSet<String>();for(var raw:tag.getList("Stats",10)){var row=(CompoundTag)raw;if(ResourceLocation.tryParse(row.getString("Type"))==null||ResourceLocation.tryParse(row.getString("Value"))==null||!row.contains("Count",3)||row.getInt("Count")<0||!keys.add(row.getString("Type")+"/"+row.getString("Value")))throw new IllegalStateException("Некорректный счётчик получателя");}
        keys.clear();for(var raw:tag.getList("Advancements",10)){var row=(CompoundTag)raw;if(ResourceLocation.tryParse(row.getString("Id"))==null||!keys.add(row.getString("Id"))||!row.contains("Definition",10)||!row.contains("Criteria",9)||row.getList("Criteria",10).isEmpty())throw new IllegalStateException("Некорректное достижение получателя");
            var criteria=new HashSet<String>();for(var item:rows(row,"Criteria",2048)){var c=(CompoundTag)item;if(c.getString("Key").isBlank()||!criteria.add(c.getString("Key"))||!c.contains("Second",4)||!c.contains("Nano",3)||c.getInt("Nano")<0||c.getInt("Nano")>=1000000000)throw new IllegalStateException("Некорректный критерий получателя");}}
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private static Stat<?> stat(CompoundTag row){
        var type=BuiltInRegistries.STAT_TYPE.getOptional(ResourceLocation.parse(row.getString("Type"))).orElseThrow(()->new IllegalStateException("Тип статистики удалён"));
        var value=type.getRegistry().getOptional(ResourceLocation.parse(row.getString("Value"))).orElseThrow(()->new IllegalStateException("Значение статистики удалено"));return ((StatType)type).get(value);
    }
    private static Map<AdvancementHolder,AdvancementProgress> decode(ServerPlayer player,CompoundTag tag){
        validate(tag);for(var raw:tag.getList("Stats",10))stat((CompoundTag)raw);
        var result=new LinkedHashMap<AdvancementHolder,AdvancementProgress>();
        for(var raw:tag.getList("Advancements",10)){var row=(CompoundTag)raw;var holder=player.server.getAdvancements().get(ResourceLocation.parse(row.getString("Id")));
            if(holder==null||!row.getCompound("Definition").equals(definition(player,holder)))throw new IllegalStateException("Определение достижения изменилось");
            var progress=new AdvancementProgress();progress.update(holder.value().requirements());
            for(var item:row.getList("Criteria",10)){var c=(CompoundTag)item;var criterion=progress.getCriterion(c.getString("Key"));if(criterion==null)throw new IllegalStateException("Критерий достижения удалён");
                ((CriterionTimeAccessor)criterion).avilixlogger$obtained(java.time.Instant.ofEpochSecond(c.getLong("Second"),c.getInt("Nano")));}
            result.put(holder,progress);
        }
        var recipes=new ServerRecipeBook();recipes.fromNbt(tag.getCompound("Recipes"),player.server.getRecipeManager());if(!recipes.toNbt().equals(tag.getCompound("Recipes")))throw new IllegalStateException("Рецепты получателя не прочитаны полностью");return result;
    }
    /** Preserve counters and advancements this activation did not touch (for example play time). */
    public static CompoundTag merge(CompoundTag current,CompoundTag from,CompoundTag to){return merge(current,from,to,false);}
    public static CompoundTag merge(CompoundTag current,CompoundTag from,CompoundTag to,boolean compensating){
        var out=current.copy();
        for(String field:List.of("Stats","Advancements")){
            var live=index(current,field);var start=index(from,field);var end=index(to,field);var keys=new HashSet<>(start.keySet());keys.addAll(end.keySet());
            for(String key:keys)if(!Objects.equals(start.get(key),end.get(key))){
                if(!Objects.equals(live.get(key),start.get(key))&&(!compensating||!Objects.equals(live.get(key),end.get(key))))throw new IllegalStateException("Статистика или достижение действия изменились");
                if(end.containsKey(key))live.put(key,end.get(key).copy());else live.remove(key);
            }
            var list=new ListTag();live.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->list.add(e.getValue()));out.put(field,list);
        }
        if(!from.getCompound("Recipes").equals(to.getCompound("Recipes"))){if(!current.getCompound("Recipes").equals(from.getCompound("Recipes"))&&(!compensating||!current.getCompound("Recipes").equals(to.getCompound("Recipes"))))throw new IllegalStateException("Рецепты награды изменились");out.put("Recipes",to.getCompound("Recipes").copy());}
        return out;
    }
    private static Map<String,CompoundTag> index(CompoundTag state,String field){
        var map=new HashMap<String,CompoundTag>();for(var raw:state.getList(field,10)){var row=(CompoundTag)raw;map.put(field.equals("Stats")?row.getString("Type")+"/"+row.getString("Value"):row.getString("Id"),row.copy());}return map;
    }
    public static void preflight(ServerPlayer player,CompoundTag tag){decode(player,tag);}
    public static void restore(ServerPlayer player,CompoundTag tag){
        var decoded=decode(player,tag);var counter=player.getStats();var stats=((PlayerStatsAccessor)counter).avilixlogger$stats();var old=new HashSet<>(stats.keySet());stats.clear();
        for(var raw:tag.getList("Stats",10)){var row=(CompoundTag)raw;stats.put(stat(row),row.getInt("Count"));}
        // The restored snapshot is already the exact desired set. The native zero updates
        // may reinsert absent counters; remove only those, with O(1) membership checks.
        var desired=new HashSet<>(stats.keySet());old.addAll(desired);
        for(var stat:old)counter.setValue(player,stat,stats.getInt(stat));counter.sendStats(player);
        stats.keySet().removeIf(s->!desired.contains(s));
        var advancements=player.getAdvancements();var access=(PlayerProgressAccessor)advancements;var changed=new HashSet<>(access.avilixlogger$progress().keySet());changed.addAll(decoded.keySet());
        advancements.stopListening();access.avilixlogger$progress().clear();access.avilixlogger$progress().putAll(decoded);
        access.avilixlogger$changed().addAll(changed);changed.forEach(access::avilixlogger$visibility);access.avilixlogger$listen(player.server.getAdvancements());advancements.flushDirty(player);
        var recipes=new ServerRecipeBook();recipes.fromNbt(tag.getCompound("Recipes"),player.server.getRecipeManager());player.getRecipeBook().copyOverData(recipes);player.getRecipeBook().sendInitialRecipeBook(player);
    }
}

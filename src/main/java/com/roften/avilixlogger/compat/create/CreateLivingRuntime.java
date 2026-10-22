package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.compat.create.mixin.AttributePermanentAccessor;
import com.roften.avilixlogger.compat.create.mixin.MobTargetRuntimeAccessor;
import com.roften.avilixlogger.core.NbtSerde;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import java.util.*;

/** Operation-bound, registry-aware runtime. An actor reference can never resolve to its human owner. */
public final class CreateLivingRuntime {
    private static final ThreadLocal<Bindings> BOUND=new ThreadLocal<>();
    private static final ThreadLocal<LivingEntity> SAVING=new ThreadLocal<>();
    private CreateLivingRuntime(){}
    public static final class Bindings {
        private final LivingEntity actor;private final Map<CompoundTag,Entity> values=new HashMap<>();
        private Bindings(LivingEntity actor){this.actor=actor;}
        public void run(Runnable action){var previous=BOUND.get();BOUND.set(this);try{action.run();}finally{if(previous==null)BOUND.remove();else BOUND.set(previous);}}
    }
    public static Bindings bind(LivingEntity actor,Collection<CompoundTag> states){
        return bind(actor,states,Map.of());
    }
    public static Bindings bind(LivingEntity actor,Collection<CompoundTag> states,Map<UUID,Entity> candidates){
        var bindings=new Bindings(actor);
        for(var state:states){if(state.isEmpty())continue;var refs=new ArrayList<CompoundTag>();
            for(String key:List.of("LastMob","LastVictim","LastPlayer","MobTarget"))refs.add(state.getCompound(key));
            var sources=new ArrayList<CompoundTag>();sources.add(state.getCompound("LastSource"));
            for(var raw:state.getCompound("Tracker").getList("Entries",10))sources.add(((CompoundTag)raw).getCompound("Source"));
            for(var source:sources){refs.add(source.getCompound("Direct"));refs.add(source.getCompound("Causing"));}
            for(var ref:refs)if(!ref.isEmpty()){
                Entity entity=candidates.get(ref.hasUUID("UUID")?ref.getUUID("UUID"):null);
                if(ref.getBoolean("Actor")||entity==null)entity=reference(ref,actor);
                if(!entity.level().dimension().location().toString().equals(ref.getString("Dimension"))||!BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(ref.getString("Type")))throw new IllegalStateException("Кандидат цели сменил тип или измерение");
                bindings.values.put(ref.copy(),entity);
            }
        }
        return bindings;
    }
    public static CompoundTag reference(Entity entity,LivingEntity actor){
        var tag=new CompoundTag();if(entity==null)return tag;
        if(entity instanceof FakePlayer&&entity!=actor)throw new IllegalStateException("Другой технический игрок не имеет адреса конструкции");
        tag.putUUID("UUID",entity.getUUID());tag.putString("Dimension",entity.level().dimension().location().toString());
        tag.putString("Type",BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());tag.putBoolean("Actor",entity==actor);return tag;
    }
    public static Entity reference(CompoundTag tag,LivingEntity actor){
        if(tag.isEmpty())return null;
        if(actor==null||!(actor.level() instanceof ServerLevel world)||!tag.hasUUID("UUID"))throw new IllegalStateException("Не найден контекст участника боя");
        if(tag.getBoolean("Actor")){
            if(!actor.getUUID().equals(tag.getUUID("UUID"))||!actor.level().dimension().location().toString().equals(tag.getString("Dimension"))
                    ||!BuiltInRegistries.ENTITY_TYPE.getKey(actor.getType()).toString().equals(tag.getString("Type")))throw new IllegalStateException("Технический игрок боя заменён");return actor;
        }
        var bound=BOUND.get();if(bound!=null&&bound.actor==actor){var entity=bound.values.get(tag);if(entity==null)throw new IllegalStateException("Ссылка боя отсутствует в preflight");return entity;}
        var level=world.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(tag.getString("Dimension"))));
        var entity=level==null?null:level.getEntity(tag.getUUID("UUID"));
        if(entity==null||entity.isRemoved()||entity instanceof FakePlayer||!BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(tag.getString("Type")))throw new IllegalStateException("Участник боя удалён или выгружен");return entity;
    }
    public static CompoundTag damage(DamageSource source,LivingEntity actor){
        var tag=new CompoundTag();if(source==null)return tag;
        if(source.getClass()!=DamageSource.class)throw new IllegalStateException("Нестандартный DamageSource не сериализуется этим контрактом");
        tag.putString("Type",source.typeHolder().unwrapKey().orElseThrow(()->new IllegalStateException("Источник урона не зарегистрирован")).location().toString());
        tag.put("Direct",reference(source.getDirectEntity(),actor));tag.put("Causing",reference(source.getEntity(),actor));
        var pos=source.sourcePositionRaw();
        if(pos!=null){if(source.getDirectEntity()!=null||source.getEntity()!=null)throw new IllegalStateException("Неподдержанное сочетание позиции и сущностей DamageSource");tag.putDouble("X",pos.x);tag.putDouble("Y",pos.y);tag.putDouble("Z",pos.z);}
        return tag;
    }
    public static DamageSource damage(CompoundTag tag,LivingEntity actor){
        if(tag.isEmpty())return null;
        var holder=actor.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE,ResourceLocation.parse(tag.getString("Type"))));
        var direct=reference(tag.getCompound("Direct"),actor);var causing=reference(tag.getCompound("Causing"),actor);
        return tag.contains("X",6)?new DamageSource(holder,new Vec3(tag.getDouble("X"),tag.getDouble("Y"),tag.getDouble("Z"))):new DamageSource(holder,direct,causing);
    }
    public static ItemStack item(CompoundTag tag,LivingEntity entity){
        if(tag.isEmpty())return ItemStack.EMPTY;
        var stack=NbtSerde.readItemStack(tag,entity.registryAccess());
        if(stack.isEmpty()||stack.getCount()!=tag.getInt("count"))
            throw new IllegalStateException("Предмет runtime не прочитан полностью");
        return stack;
    }
    public static CompoundTag entitySnapshot(ServerLevel level,Entity entity){
        var previous=SAVING.get();if(entity instanceof LivingEntity living)SAVING.set(living);
        try{return NbtSerde.snapshotEntity(level,entity);}finally{if(previous==null)SAVING.remove();else SAVING.set(previous);}
    }
    public static boolean savingInventory(Player player){return SAVING.get()==player;}
    /** Same slot addresses as Inventory.save, with expanded counts preserved. */
    public static ListTag inventorySnapshot(Player player){
        var inventory=player.getInventory();if(inventory.getContainerSize()!=41)throw new IllegalStateException("Неизвестный размер инвентаря runtime");
        var list=new ListTag();
        for(int slot=0;slot<41;slot++){
            var stack=inventory.getItem(slot);if(stack.isEmpty())continue;
            var value=NbtSerde.snapshotItemStack(stack,player.registryAccess());
            if(value==null)throw new IllegalStateException("Предмет инвентаря runtime не сохранён");
            value.putByte("Slot",(byte)(slot<36?slot:slot<40?slot+64:150));list.add(value);
        }
        return list;
    }
    private static int inventorySlot(CompoundTag value){
        int slot=value.getByte("Slot")&255;
        return slot<36?slot:slot>=100&&slot<104?slot-64:slot==150?40:-1;
    }
    private static void validateInventory(CompoundTag persistent){
        var inventory=list(persistent,"Inventory");var slots=new HashSet<Integer>();
        if(inventory.size()>41||!persistent.contains("XpSeed",3)||!persistent.contains("SelectedItemSlot",3)||persistent.getInt("SelectedItemSlot")<0||persistent.getInt("SelectedItemSlot")>=9)
            throw new IllegalStateException("Неполный инвентарь runtime");
        for(var raw:inventory){var value=(CompoundTag)raw;int slot=inventorySlot(value);
            if(!value.contains("Slot",1)||slot<0||!slots.add(slot)||!value.contains("id",8)||!value.contains("count",3)||value.getInt("count")<=0)
                throw new IllegalStateException("Некорректный слот инвентаря runtime");}
    }
    public static CompoundTag capture(LivingEntity entity,LivingEntity actor){
        var tag=((CreateLivingRuntimeAccess)entity).avilixlogger$livingRuntime(actor);
        var attributes=new ListTag();
        for(var holder:BuiltInRegistries.ATTRIBUTE.holders().sorted(Comparator.comparing(h->h.key().location().toString())).toList()){
            if(!entity.getAttributes().hasAttribute(holder))continue;
            var instance=entity.getAttribute(holder);if(instance==null)continue;
            var row=new CompoundTag();row.putString("Id",holder.key().location().toString());row.putDouble("Base",instance.getBaseValue());var modifiers=new ListTag();
            var permanent=((AttributePermanentAccessor)instance).avilixlogger$permanent();
            for(var modifier:instance.getModifiers().stream().sorted(Comparator.comparing(m->m.id().toString())).toList()){
                var value=new CompoundTag();value.put("Value",AttributeModifier.CODEC.encodeStart(NbtOps.INSTANCE,modifier).result().orElseThrow(()->new IllegalStateException("Нет снимка модификатора")));
                value.putBoolean("Permanent",permanent.containsKey(modifier.id()));modifiers.add(value);
            }
            row.put("Modifiers",modifiers);attributes.add(row);
        }
        // Materialize supported attributes before saving native data so capture itself cannot
        // make the next snapshot appear to contain a new attribute definition.
        var persistent=new CompoundTag();var previous=SAVING.get();SAVING.set(entity);
        try{if(entity instanceof net.minecraft.server.level.ServerPlayer&&!(entity instanceof FakePlayer))((CreateHumanRuntimeAccess)entity).avilixlogger$savePlayerRuntime(persistent);else entity.addAdditionalSaveData(persistent);}finally{if(previous==null)SAVING.remove();else SAVING.set(previous);}
        normalize(persistent);tag.put("Persistent",persistent);
        tag.put("Attributes",attributes);if(entity instanceof Player player){var food=new CompoundTag();player.getFoodData().addAdditionalSaveData(food);tag.put("Food",food);}
        if(entity instanceof Mob mob){tag.put("MobTarget",reference(mob.getTarget(),actor));tag.putBoolean("Aggressive",mob.isAggressive());}
        if(tag.sizeInBytes()>2*1024*1024)throw new IllegalStateException("Runtime активатора превышает 2 MiB");return tag;
    }
    /** Native attribute maps do not promise iteration order after an entity is recreated. */
    public static void normalize(CompoundTag persistent){
        // NeoForge serializes a cure set in iteration order. Compare the actual cure
        // members, not their incidental order, across native save/load round-trips.
        if(persistent.contains("active_effects",9))for(var raw:persistent.getList("active_effects",10)){
            var effect=(CompoundTag)raw;
            if(!(effect.get("neoforge:cures") instanceof ListTag cures)||cures.getElementType()!=Tag.TAG_STRING||cures.size()<2)continue;
            boolean alreadySorted=true;
            for(int i=1;i<cures.size();i++)if(cures.getString(i-1).compareTo(cures.getString(i))>0){alreadySorted=false;break;}
            if(alreadySorted)continue;
            var ordered=cures.stream().map(Tag::getAsString).sorted().toList();
            var canonical=new ListTag();for(var cure:ordered)canonical.add(StringTag.valueOf(cure));
            effect.put("neoforge:cures",canonical);
        }
        if(!persistent.contains("attributes",9))return;var sorted=new ListTag();
        for(var raw:persistent.getList("attributes",10).stream().sorted(Comparator.comparing(t->((CompoundTag)t).getString("id"))).toList()){
            var row=((CompoundTag)raw).copy();if(row.contains("modifiers",9)){var modifiers=new ListTag();row.getList("modifiers",10).stream().sorted(Comparator.comparing(t->((CompoundTag)t).getString("id"))).forEach(t->modifiers.add(t.copy()));row.put("modifiers",modifiers);}sorted.add(row);
        }
        persistent.put("attributes",sorted);
    }
    private record AttributeState(AttributeInstance instance,double base,List<AttributeModifier> permanent,List<AttributeModifier> temporary){}
    private static List<AttributeState> decode(LivingEntity entity,LivingEntity actor,CompoundTag tag){
        validate(tag);validateReferences(tag,actor);
        if(entity instanceof Player){
            if(!tag.contains("Food",10))throw new IllegalStateException("Нет снимка питания активатора");
            validateInventory(tag.getCompound("Persistent"));
            for(var raw:tag.getCompound("Persistent").getList("Inventory",10))item((CompoundTag)raw,entity);
        }
        if(entity instanceof Mob&&(!tag.contains("MobTarget",10)||!tag.contains("Aggressive",1)))throw new IllegalStateException("Нет состояния цели моба");
        var ops=entity.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var persistent=tag.getCompound("Persistent");
        if(persistent.contains("active_effects"))for(var raw:list(persistent,"active_effects"))
            MobEffectInstance.CODEC.parse(ops,raw).result().orElseThrow(()->new IllegalStateException("Эффект runtime не прочитан полностью"));
        var states=new ArrayList<AttributeState>();
        for(var raw:tag.getList("Attributes",10)){var row=(CompoundTag)raw;var key=ResourceLocation.parse(row.getString("Id"));
            var holder=BuiltInRegistries.ATTRIBUTE.getHolder(ResourceKey.create(Registries.ATTRIBUTE,key)).orElseThrow(()->new IllegalStateException("Атрибут runtime отсутствует"));
            var instance=entity.getAttribute(holder);if(instance==null)throw new IllegalStateException("Цель больше не имеет атрибут runtime");
            var permanent=new ArrayList<AttributeModifier>();var temporary=new ArrayList<AttributeModifier>();
            for(var value:row.getList("Modifiers",10)){var modifier=(CompoundTag)value;var decoded=AttributeModifier.CODEC.parse(NbtOps.INSTANCE,modifier.get("Value")).result().orElseThrow(()->new IllegalStateException("Модификатор runtime не прочитан"));
                (modifier.getBoolean("Permanent")?permanent:temporary).add(decoded);}
            states.add(new AttributeState(instance,row.getDouble("Base"),permanent,temporary));
        }
        return states;
    }
    /** Called for both sides during preflight, before a restore can mutate any endpoint. */
    public static void validateForRestore(LivingEntity entity,LivingEntity actor,CompoundTag tag){decode(entity,actor,project(tag,entity.tickCount));}
    public static void restore(LivingEntity entity,LivingEntity actor,CompoundTag tag){
        tag=project(tag,entity.tickCount);
        var states=decode(entity,actor,tag);
        ((CreateLivingRuntimeAccess)entity).avilixlogger$clearEffectsForRestore();
        var nativeData=tag.getCompound("Persistent").copy();
        // Inventory.load uses the vanilla count-limited codec. Decode these stacks
        // separately, after native data is loaded, without changing the saved snapshot.
        if(entity instanceof Player)nativeData.put("Inventory",new ListTag());
        if(entity instanceof net.minecraft.server.level.ServerPlayer&&!(entity instanceof FakePlayer))((CreateHumanRuntimeAccess)entity).avilixlogger$loadPlayerRuntime(nativeData);
        else entity.readAdditionalSaveData(nativeData);
        for(var state:states){state.instance.removeModifiers();state.instance.setBaseValue(state.base);for(var modifier:state.permanent)state.instance.addPermanentModifier(modifier);for(var modifier:state.temporary)state.instance.addTransientModifier(modifier);}
        // Native loading clamps health before transient max-health modifiers are back.
        // Reapply these values only after restoring the complete attribute set.
        entity.setHealth(tag.getCompound("Persistent").getFloat("Health"));
        entity.setAbsorptionAmount(tag.getCompound("Persistent").getFloat("AbsorptionAmount"));
        if(entity instanceof Player player){
            player.getFoodData().readAdditionalSaveData(tag.getCompound("Food"));
            ((CreatePlayerRuntimeAccess)player).avilixlogger$enchantmentSeed(tag.getCompound("Persistent").getInt("XpSeed"));
            var inventory=player.getInventory();inventory.clearContent();
            for(var raw:tag.getCompound("Persistent").getList("Inventory",10)){var value=(CompoundTag)raw;inventory.setItem(inventorySlot(value),item(value,player));}
        }
        ((CreateLivingRuntimeAccess)entity).avilixlogger$livingRuntime(tag,actor);
        if(entity instanceof Mob mob){var target=reference(tag.getCompound("MobTarget"),actor);if(target!=null&&!(target instanceof LivingEntity))throw new IllegalStateException("Цель моба сменила тип");
            ((MobTargetRuntimeAccessor)mob).avilixlogger$target((LivingEntity)target);mob.setAggressive(tag.getBoolean("Aggressive"));}
    }
    public static void validateReferences(CompoundTag tag,LivingEntity actor){
        for(String field:List.of("LastMob","LastVictim","LastPlayer","MobTarget")){
            var ref=reference(tag.getCompound(field),actor);
            if(ref!=null&&(!(ref instanceof LivingEntity)||field.equals("LastPlayer")&&!(ref instanceof Player)))throw new IllegalStateException("Тип участника боя изменён");
        }
        damage(tag.getCompound("LastSource"),actor);item(tag.getCompound("UseItem"),actor);
        for(var raw:tag.getCompound("Tracker").getList("Entries",10))damage(((CompoundTag)raw).getCompound("Source"),actor);
    }
    public static boolean matches(LivingEntity entity,LivingEntity actor,CompoundTag expected){
        return capture(entity,actor).equals(project(expected,entity.tickCount));
    }
    /** Only deterministic native counters are aged; health, inventory, effects, AI and
     * combat entries still have to match. An intervening action is a conflict. */
    public static CompoundTag project(CompoundTag original,int tick){
        if(!original.contains("Tick",3)||tick<original.getInt("Tick"))throw new IllegalStateException("Цель была перезагружена после действия активатора");
        var tag=original.copy();normalize(tag.getCompound("Persistent"));long elapsed=(long)tick-original.getInt("Tick");tag.putInt("Tick",tick);
        for(String field:List.of("AttackStrength","NoAction"))tag.putInt(field,(int)Math.min(Integer.MAX_VALUE,(long)original.getInt(field)+elapsed));
        if(tag.contains("Food",10)&&elapsed>0)tag.putInt("NoAction",0);
        for(String field:List.of("HurtTime","Invulnerable","LastPlayerTime"))tag.putInt(field,(int)Math.max(0,(long)original.getInt(field)-elapsed));
        if(elapsed>original.getInt("LastPlayerTime"))tag.put("LastPlayer",new CompoundTag());
        if((long)tick-original.getInt("LastMobAt")>100)tag.put("LastMob",new CompoundTag());
        tag.getCompound("Persistent").putShort("HurtTime",(short)tag.getInt("HurtTime"));
        return tag;
    }
    /** Detached validation; no worlds or registries are read by the history worker. */
    public static void validate(CompoundTag tag){
        if(tag.getInt("Format")!=1||!tag.contains("Tick",3)||tag.getInt("Tick")<0||!tag.contains("Persistent",10)||!tag.contains("Attributes",9)||!tag.contains("Tracker",10)
                ||!tag.contains("UseItem",10)||tag.sizeInBytes()>2*1024*1024)throw new IllegalStateException("Неполный runtime активатора");
        for(String key:List.of("AttackStrength","NoAction","HurtTime","HurtDuration","DeathTime","Invulnerable","LastMobAt","LastVictimAt","LastPlayerTime","UseRemaining"))
            if(!tag.contains(key,3)||tag.getInt(key)<0)throw new IllegalStateException("Некорректный счётчик runtime: "+key);
        if(!tag.contains("LastHurt",5)||!Float.isFinite(tag.getFloat("LastHurt"))||tag.getFloat("LastHurt")<0||!tag.contains("LastStamp",4)||!tag.contains("Flags",1)||!tag.contains("Dead",1))throw new IllegalStateException("Неполное состояние боя");
        var tracker=tag.getCompound("Tracker");
        for(String key:List.of("LastDamageTime","Start","End"))if(!tracker.contains(key,3)||tracker.getInt(key)<0)throw new IllegalStateException("Неполный счётчик истории боя");
        if(!tracker.contains("InCombat",1)||!tracker.contains("TakingDamage",1))throw new IllegalStateException("Нет состояния истории боя");
        var entries=list(tracker,"Entries");if(entries.size()>128)throw new IllegalStateException("Неполная история боя");
        for(var raw:entries){var entry=(CompoundTag)raw;if(!entry.contains("Source",10)||entry.getCompound("Source").isEmpty()||!entry.contains("Damage",5)||!Float.isFinite(entry.getFloat("Damage"))||entry.getFloat("Damage")<0||!entry.contains("FallDistance",5)||!Float.isFinite(entry.getFloat("FallDistance")))throw new IllegalStateException("Повреждена запись боя");damageSchema(entry.getCompound("Source"));}
        var attributes=list(tag,"Attributes");if(attributes.size()>512)throw new IllegalStateException("Слишком много атрибутов runtime");var ids=new HashSet<String>();
        for(var raw:attributes){var attribute=(CompoundTag)raw;var modifiers=list(attribute,"Modifiers");
            if(ResourceLocation.tryParse(attribute.getString("Id"))==null||!ids.add(attribute.getString("Id"))||!attribute.contains("Base",6)||!Double.isFinite(attribute.getDouble("Base"))||modifiers.size()>512)throw new IllegalStateException("Повреждён атрибут runtime");
            var modifierIds=new HashSet<ResourceLocation>();for(var value:modifiers){var modifier=(CompoundTag)value;
                if(!modifier.contains("Permanent",1)||!modifier.contains("Value",10))throw new IllegalStateException("Неполный модификатор runtime");
                var decoded=AttributeModifier.CODEC.parse(NbtOps.INSTANCE,modifier.get("Value")).result().orElseThrow(()->new IllegalStateException("Повреждён модификатор runtime"));
                if(!Double.isFinite(decoded.amount())||!modifierIds.add(decoded.id()))throw new IllegalStateException("Повторный или некорректный модификатор runtime");
            }
        }
        var persistent=tag.getCompound("Persistent");
        if(!persistent.contains("Health",5)||!Float.isFinite(persistent.getFloat("Health"))||persistent.getFloat("Health")<0
                ||!persistent.contains("AbsorptionAmount",5)||!Float.isFinite(persistent.getFloat("AbsorptionAmount"))||persistent.getFloat("AbsorptionAmount")<0
                ||!persistent.contains("attributes",9))throw new IllegalStateException("Нет полного здоровья/атрибутов runtime");
        list(persistent,"attributes");
        if(persistent.contains("Inventory"))validateInventory(persistent);
        for(String key:List.of("LastMob","LastVictim","LastPlayer")){if(!tag.contains(key,10))throw new IllegalStateException("Отсутствует ссылка боя: "+key);referenceSchema(tag.getCompound(key));}
        if(!tag.contains("LastSource",10))throw new IllegalStateException("Нет последнего источника урона");damageSchema(tag.getCompound("LastSource"));
        var useItem=tag.getCompound("UseItem");
        if(!useItem.isEmpty()&&(!useItem.contains("id",8)||!useItem.contains("count",3)||useItem.getInt("count")<=0))
            throw new IllegalStateException("Некорректный предмет в использовании");
        if(tag.contains("MobTarget")){if(!tag.contains("MobTarget",10)||!tag.contains("Aggressive",1))throw new IllegalStateException("Нет состояния цели моба");referenceSchema(tag.getCompound("MobTarget"));}
        if(tag.contains("Food")){var food=tag.getCompound("Food");if(!food.contains("foodLevel",3)||food.getInt("foodLevel")<0||food.getInt("foodLevel")>20||!food.contains("foodTickTimer",3)||food.getInt("foodTickTimer")<0)throw new IllegalStateException("Некорректное питание runtime");
            for(String key:List.of("foodSaturationLevel","foodExhaustionLevel"))if(!food.contains(key,5)||!Float.isFinite(food.getFloat(key))||food.getFloat(key)<0)throw new IllegalStateException("Неполное питание runtime");}
    }
    private static ListTag list(CompoundTag tag,String key){
        if(!(tag.get(key) instanceof ListTag value)||!value.isEmpty()&&value.getElementType()!=10)throw new IllegalStateException("Некорректный список runtime: "+key);return value;
    }
    private static void referenceSchema(CompoundTag tag){
        if(tag.isEmpty())return;
        if(!tag.hasUUID("UUID")||ResourceLocation.tryParse(tag.getString("Dimension"))==null||ResourceLocation.tryParse(tag.getString("Type"))==null||!tag.contains("Actor",1))throw new IllegalStateException("Неполная ссылка участника боя");
    }
    private static void damageSchema(CompoundTag tag){
        if(tag.isEmpty())return;
        if(ResourceLocation.tryParse(tag.getString("Type"))==null||!tag.contains("Direct",10)||!tag.contains("Causing",10))throw new IllegalStateException("Неполный источник урона");
        referenceSchema(tag.getCompound("Direct"));referenceSchema(tag.getCompound("Causing"));
        boolean positioned=tag.contains("X")||tag.contains("Y")||tag.contains("Z");
        if(positioned){for(String key:List.of("X","Y","Z"))if(!tag.contains(key,6)||!Double.isFinite(tag.getDouble(key)))throw new IllegalStateException("Некорректная позиция урона");
            if(!tag.getCompound("Direct").isEmpty()||!tag.getCompound("Causing").isEmpty())throw new IllegalStateException("Позиционный источник содержит сущности");}
    }
}

package com.roften.avilixlogger.compat.create;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import java.util.*;
/** Sparse endpoints for one actual activation. No snapshots on idle ticks. */
public final class CreateDeployerTransaction implements CreateDeployerHooks.Observer {
    // Keep the actual native AI/controllers for recently removed known mobs. NBT alone
    // cannot reconstruct running goals. Eviction/restart must refuse, never synthesize AI.
    private static final LinkedHashMap<UUID,Entity> RETAINED=new LinkedHashMap<>();
    public static void clearRetained(){RETAINED.clear();}
    private static UUID retain(Entity entity){var token=UUID.randomUUID();RETAINED.put(token,entity);while(RETAINED.size()>256)RETAINED.remove(RETAINED.keySet().iterator().next());return token;}
    public static Entity retained(CompoundTag terminal,ServerLevel level){
        var entity=terminal.hasUUID("Retained")?RETAINED.get(terminal.getUUID("Retained")):null;
        if(entity==null||entity.level()!=level||!entity.getUUID().equals(terminal.getUUID("Entity")))throw new IllegalStateException("Нативный AI умершей цели больше не доступен; возврат из одного NBT запрещён");return entity;
    }
    private static final ThreadLocal<Capture> CURRENT=new ThreadLocal<>();
    private record Address(ServerLevel level,BlockPos pos){}
    public static void install(){CreateDeployerHooks.install(new CreateDeployerTransaction());}
    public boolean before(net.minecraft.world.level.Level level,BlockPos pos){var c=CURRENT.get();if(c==null||c.reading||CartAuditContext.restoring()||!(level instanceof ServerLevel s)||!s.getServer().isSameThread())return false;c.block(s,pos);return true;}
    public static void unsupported(String reason){var c=CURRENT.get();if(c!=null){c.complete=false;c.reason=reason;}}
    public static void entityBefore(Entity entity){var c=CURRENT.get();if(c==null||entity==null||entity==c.player)return;if(entity instanceof net.neoforged.neoforge.common.util.FakePlayer){unsupported("Другой технический игрок не имеет собственного адресуемого endpoint");return;}c.target(entity,false);}
    public static void join(EntityJoinLevelEvent event){var c=CURRENT.get();if(c!=null&&!event.isCanceled()&&!event.loadedFromDisk()&&event.getLevel() instanceof ServerLevel)c.target(event.getEntity(),true);}
    public static Capture begin(MovementContext context,DeployerFakePlayer player,BlockPos target){return new Capture(context,player,target);}
    public static boolean supportedItem(ItemStack stack,DeployerFakePlayer player){
        try{CreateDeployerItemContracts.describe(stack);return true;}catch(IllegalStateException unsupported){return false;}
    }
    public static final class Use implements AutoCloseable {
        private final Capture capture;private final String action;private final ListTag before;private ItemStack returned;private boolean closed;
        private Use(Capture capture,String action){
            this.capture=capture;this.action=action;ListTag saved=null;
            if(capture!=null)try{
                capture.contract(capture.player.getMainHandItem());
                saved=inventory(capture.player);
            }catch(RuntimeException failure){capture.complete=false;capture.reason="Начало использования: "+failure.getMessage();}
            before=saved;
        }
        public void returned(ItemStack stack){returned=stack;}
        public void close(){
            if(closed)return;closed=true;if(capture==null||before==null)return;
            try{
                var after=inventory(capture.player);
                if(returned!=null){
                    int slot=capture.player.getInventory().selected;after.removeIf(t->((CompoundTag)t).getInt("Slot")==slot);
                    if(!returned.isEmpty()){var item=NbtSerde.snapshotItemStack(returned,capture.player.registryAccess());if(item==null)throw new IllegalStateException("Нет снимка остатка использования");item.putInt("Slot",slot);after.add(item);}
                    var sorted=new ListTag();after.stream().sorted(Comparator.comparingInt(t->((CompoundTag)t).getInt("Slot"))).forEach(sorted::add);after=sorted;
                }
                if(before.equals(after))return;var row=new CompoundTag();row.putString("Action",action);row.put("Before",before);row.put("After",after);
                capture.charge(row);if(capture.uses.size()>=64)throw new IllegalStateException("Слишком много вложенных использований");capture.uses.add(row);
            }catch(RuntimeException failure){capture.complete=false;capture.reason="Завершение использования: "+failure.getMessage();}
        }
    }
    public static Use using(String action){return new Use(CURRENT.get(),action);}
    private static void preparePlayer(net.minecraft.world.entity.player.Player player){
        if(net.neoforged.fml.ModList.get().isLoaded("backpacked"))com.roften.avilixlogger.compat.backpacked.BackpackDeployerContract.prepare(player);
    }
    private static ListTag inventory(DeployerFakePlayer player){
        var list=new ListTag();for(int slot=0;slot<41;slot++){var stack=player.getInventory().getItem(slot);if(stack.isEmpty())continue;var tag=NbtSerde.snapshotItemStack(stack,player.registryAccess());if(tag==null)throw new IllegalStateException("Нет снимка использования предмета");tag.putInt("Slot",slot);list.add(tag);}return list;
    }
    public static CompoundTag hand(MovementContext context,DeployerFakePlayer player){
        preparePlayer(player);var tag=new CompoundTag();var entity=context.contraption.entity;
        tag.putUUID("Construction",CreateContraptionSources.id(entity));tag.putUUID("StorageEntity",entity.getUUID());tag.putString("ConstructionKind",CreateContraptionSources.kind(entity).name());tag.putString("Dimension",entity.level().dimension().location().toString());tag.putLong("LocalPos",context.localPos.asLong());
        tag.put("State",NbtUtils.writeBlockState(context.state));var settings=context.blockEntityData.copy();settings.remove("Inventory");tag.put("Settings",settings);
        var items=new ListTag();var inventory=player.getInventory();for(int i=0;i<inventory.getContainerSize();i++){var stack=inventory.getItem(i);if(stack.isEmpty())continue;var item=NbtSerde.snapshotItemStack(stack,player.registryAccess());if(item==null)throw new IllegalStateException("Нет снимка инвентаря активатора");item.putInt("Slot",i);items.add(item);}
        tag.putInt("Size",inventory.getContainerSize());tag.putInt("Selected",inventory.selected);tag.put("Inventory",items);
        var runtime=((CreateDeployerRuntimeAccess)player).avilixlogger$deployerRuntime();
        if(context.data.contains("Timer",3))runtime.putInt("Timer",context.data.getInt("Timer"));
        tag.put("Runtime",runtime);tag.put("External",CreateExternalPlayerState.capture(player));return tag;
    }
    public static CompoundTag entity(Entity entity,boolean exists){
        var capture=CURRENT.get();return entity(entity,exists,capture==null?null:capture.player);
    }
    public static CompoundTag entity(Entity entity,boolean exists,net.minecraft.world.entity.LivingEntity actor){
        if(entity instanceof net.minecraft.world.entity.player.Player human)preparePlayer(human);
        var tag=new CompoundTag();tag.putUUID("Entity",entity.getUUID());tag.putString("Dimension",entity.level().dimension().location().toString());tag.putString("Type",net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());tag.putLong("Pos",entity.blockPosition().asLong());tag.putBoolean("Exists",exists);
        if(exists){if(entity instanceof net.minecraft.world.entity.LivingEntity living){if(actor==null)throw new IllegalStateException("Нет технического игрока снимка цели");tag.put("LivingRuntime",CreateLivingRuntime.capture(living,actor));if(!(living instanceof net.minecraft.world.entity.player.Player))tag.put("Scores",CreateScoreRuntime.capture(living));}
            if(entity instanceof net.minecraft.server.level.ServerPlayer human&&!(human instanceof net.neoforged.neoforge.common.util.FakePlayer))tag.put("Human",CreateHumanRestore.capture(human));
            var nbt=CreateLivingRuntime.entitySnapshot((ServerLevel)entity.level(),entity);if(nbt==null)throw new IllegalStateException("Нет снимка цели активатора");tag.put("NBT",nbt);}
        return tag;
    }
    public static boolean supportedEntity(String type){return Set.of("minecraft:player","minecraft:item","minecraft:experience_orb","minecraft:pig","minecraft:cow","minecraft:sheep","minecraft:chicken","minecraft:armor_stand","minecraft:item_frame","minecraft:glow_item_frame").contains(type);}
    public static final class Capture implements AutoCloseable {
        final MovementContext context;final DeployerFakePlayer player;final Capture previous;final CompoundTag beforeHand;final CreateCargoAudit.Capture cargo;final boolean nestedAutomation;
        final LinkedHashMap<Address,CompoundTag> blocks=new LinkedHashMap<>();final LinkedHashMap<Entity,CompoundTag> targets=new LinkedHashMap<>();
        final ListTag uses=new ListTag();final LinkedHashMap<String,CompoundTag> contracts=new LinkedHashMap<>();boolean complete=true,closed,reading;private long bytes;private CompoundTag before,after;private String reason;
        Capture(MovementContext context,DeployerFakePlayer player,BlockPos target){this.context=context;this.player=player;preparePlayer(player);beforeHand=hand(context,player);previous=CURRENT.get();nestedAutomation=CartAutomationAudit.capturingChanges();CartStorageAudit.ensureBound(context.contraption.entity);cargo=CreateCargoAudit.captureChanges(context.contraption.entity);CURRENT.set(this);try{charge(beforeHand);contract(player.getMainHandItem());block((ServerLevel)context.world,target);}catch(RuntimeException failure){close();throw failure;}}
        void contract(ItemStack stack){
            try{var tag=CreateDeployerItemContracts.describe(stack);if(!tag.isEmpty()&&contracts.containsKey(tag.getString("Item"))&&!contracts.get(tag.getString("Item")).equals(tag))throw new IllegalStateException("Предмет сменил контракт внутри действия");if(!tag.isEmpty()&&!contracts.containsKey(tag.getString("Item"))){if(contracts.size()>=64)throw new IllegalStateException("Слишком много контрактов предметов");charge(tag);contracts.put(tag.getString("Item"),tag);}}
            catch(IllegalStateException failure){complete=false;reason=failure.getMessage();}
        }
        void block(ServerLevel level,BlockPos pos){
            var key=new Address(level,pos.immutable());if(blocks.containsKey(key))return;
            if(blocks.size()>=64){complete=false;return;}
            reading=true;try{var tag=CreateStructuralAudit.world(level,pos);charge(tag);if(tag.contains("Runtime",10)||tag.contains("KineticRuntime",10))complete=false;blocks.put(key,tag);}catch(RuntimeException failure){complete=false;}finally{reading=false;}
        }
        void target(Entity entity,boolean created){
            if(targets.containsKey(entity))return;
            if(targets.size()>=32||entity.level()!=context.world){complete=false;return;}
            String type=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            if(!supportedEntity(type)||entity.isPassenger()||!entity.getPassengers().isEmpty()){complete=false;return;}
            if(entity instanceof net.minecraft.world.entity.Mob mob&&mob.isLeashed()){complete=false;reason="Связь поводком требует отдельного контракта";return;}
            try{var tag=entity(entity,!created);charge(tag);targets.put(entity,tag);}catch(RuntimeException failure){complete=false;reason="Снимок цели: "+failure.getMessage();}
        }
        private void charge(CompoundTag tag){bytes+=tag.sizeInBytes();if(bytes>4L*1024*1024)throw new IllegalStateException("Снимок активатора превышает 4 MiB");}
        public void attach(CompoundTag b,CompoundTag a,boolean completed){
            if(before!=null){b.merge(before);a.merge(after);return;}
            reading=true;
            if(nestedAutomation){complete=false;reason="Вложенный обмен автоматизации требует общего снимка обеих операций";}
            var oldWorld=new ListTag();var newWorld=new ListTag();var oldEntities=new ListTag();var newEntities=new ListTag();
            for(var entry:blocks.entrySet())try{var old=entry.getValue();var now=CreateStructuralAudit.world(entry.getKey().level,entry.getKey().pos);charge(now);if(!old.equals(now)){oldWorld.add(old.copy());newWorld.add(now);}}catch(RuntimeException failure){complete=false;}
            for(var entry:targets.entrySet())try{var old=entry.getValue();
                boolean terminal=entry.getKey() instanceof net.minecraft.world.entity.LivingEntity&&!entry.getKey().isAlive()&&old.getBoolean("Exists");
                if(terminal&&!Set.of("minecraft:pig","minecraft:cow","minecraft:sheep","minecraft:chicken").contains(old.getString("Type"))){complete=false;reason="У смерти этой цели нет полного контракта";}
                var now=entity(entry.getKey(),!entry.getKey().isRemoved()&&!terminal);if(now.contains("Human",10))CreateExternalPlayerState.validatePair(old.getCompound("Human").getCompound("External"),now.getCompound("Human").getCompound("External"));if(terminal){if(Set.of("minecraft:pig","minecraft:cow","minecraft:sheep","minecraft:chicken").contains(old.getString("Type")))now.putUUID("Retained",retain(entry.getKey()));now.putBoolean("Terminal",true);now.put("Death",entity(entry.getKey(),true));}charge(now);if(!old.equals(now)){oldEntities.add(old.copy());newEntities.add(now);}}catch(RuntimeException failure){complete=false;}
            var oldCargo=new ListTag();var newCargo=new ListTag();int creativeIO=0;
            if(!cargo.complete())complete=false;
            for(var row:cargo.rows()){
                var old=row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_BEFORE);var now=row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER);
                if(!(old instanceof CompoundTag x)||!(now instanceof CompoundTag y)){complete=false;continue;}
                if(row.type!=ActionType.CART_CONTENT_CHANGE){
                    if(row.type!=ActionType.CART_TRANSFER||!CreateCreativeTransferPlan.observed(x,y)){complete=false;continue;}
                    if(!y.getString("ResourceKind").equals("item")||!Set.of("deployer_take","deployer_return").contains(y.getString("Operation"))){
                        complete=false;reason="Creative IO активатора требует отдельного полного контракта действия";
                    }
                }
                try{charge(x);charge(y);oldCargo.add(x);newCargo.add(y);if(y.getString("Kind").equals("storage_io"))creativeIO++;}catch(RuntimeException failure){complete=false;}
            }

            if(!player.isAlive()){complete=false;reason="Технический игрок умер во время действия";}
            var afterHand=hand(context,player);charge(afterHand);try{CreateExternalPlayerState.validatePair(beforeHand.getCompound("External"),afterHand.getCompound("External"));}catch(IllegalStateException failure){complete=false;reason=failure.getMessage();}var transfer=UUID.randomUUID();
            for(var tag:List.of(b,a)){tag.putUUID("Transfer",transfer);tag.putInt("JointFormat",6);if(creativeIO>0)tag.putInt("CreativeIO",creativeIO);tag.putBoolean("Complete",completed&&complete);if(reason!=null)tag.putString("JointFailure",reason);}
            var itemContracts=new ListTag();contracts.values().forEach(t->itemContracts.add(t.copy()));
            for(var tag:List.of(b,a)){tag.put("ItemContracts",itemContracts.copy());tag.putInt("ContractCount",itemContracts.size());tag.putInt("WorldCount",oldWorld.size());tag.putInt("TargetCount",oldEntities.size());tag.putInt("CargoCount",oldCargo.size());tag.putInt("UseCount",uses.size());tag.put("Uses",uses.copy());}
            b.put("Cargo",oldCargo);a.put("Cargo",newCargo);b.put("Hand",beforeHand.copy());a.put("Hand",afterHand);b.put("World",oldWorld);a.put("World",newWorld);b.put("Targets",oldEntities);a.put("Targets",newEntities);
            before=b.copy();after=a.copy();reading=false;
        }
        public boolean changed(){reading=true;try{if(!cargo.rows().isEmpty()||!cargo.complete()||!beforeHand.equals(hand(context,player)))return true;for(var entry:blocks.entrySet())if(!entry.getValue().equals(CreateStructuralAudit.world(entry.getKey().level,entry.getKey().pos)))return true;for(var entry:targets.entrySet())if(!entry.getValue().equals(entity(entry.getKey(),!entry.getKey().isRemoved())))return true;return !complete;}finally{reading=false;}}
        public void close(){if(!closed){closed=true;cargo.close();if(previous==null)CURRENT.remove();else CURRENT.set(previous);}}
    }
}

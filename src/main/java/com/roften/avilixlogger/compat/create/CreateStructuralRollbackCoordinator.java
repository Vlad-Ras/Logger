package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.*;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Exact undo of one native controller disassembly, with bounded mutation and compensation. */
public final class CreateStructuralRollbackCoordinator {
    private record Preview(UUID construction,long entry,long expires){}
    private static final Map<UUID,Preview> PREVIEWS=new HashMap<>();
    private static final Map<UUID,Long> REQUESTS=new HashMap<>();
    private static long sequence,generation;private static boolean preparing;private static UUID preparingActor;private static Job active;
    private CreateStructuralRollbackCoordinator(){}
    public static boolean busy(){return preparing||active!=null;}
    private static CreateStructuralRollbackPlan read(LogStorage storage,UUID id,long entry)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        if(!AsyncLogProcessor.awaitIdle(deadline)||!storage.awaitVisible(deadline))throw new IllegalStateException("Журнал ещё не виден в БД");
        var query=new LogQuery();query.dim="*";query.constructionId=id;query.requireDetails=true;query.afterId=entry-1;query.beforeId=entry+1;query.untilTs=Long.MAX_VALUE;query.limit=1;
        var found=storage.queryReverse(query);
        if(found.size()!=1||found.getFirst().id!=entry||found.getFirst().type!=ActionType.CART_DISASSEMBLE||!id.equals(CreateContraptionKind.id(found.getFirst().source)))throw new IllegalStateException("Полная запись разборки не найдена");
        var row=found.getFirst();var plan=CreateStructuralRollbackPlan.prepare(NbtSerde.fromSnbt(row.beBefore),NbtSerde.fromSnbt(row.beAfter));verifyLatest(storage,plan,entry);return plan;
    }
    static void verifyLatest(LogStorage storage,CreateStructuralRollbackPlan plan,long entry){
        for(var id:plan.constructions()){
            var query=new LogQuery();query.dim="*";query.constructionId=id;query.requireDetails=true;query.beforeId=Long.MAX_VALUE;query.untilTs=Long.MAX_VALUE;query.limit=512;
            int examined=0;
            for(var row:storage.queryReverse(query)){
                if(row.id<=entry)break;examined++;
                if(!id.equals(CreateContraptionKind.id(row.source)))throw new IllegalStateException("БД вернула чужую историю");
                var tag=NbtSerde.fromSnbt(row.beAfter);
                if(row.type==ActionType.ENTITY_SPAWN&&CreateContraptionKind.phase(row.source).equals("structural_disassemble")
                        &&plan.effects().stream().anyMatch(effect->effect.getUUID("Entity").equals(row.entityUuid)))continue;
                if(row.type!=ActionType.CART_DISASSEMBLE||tag==null||!tag.hasUUID("Operation")||!plan.operation().equals(tag.getUUID("Operation")))throw new IllegalStateException("После разборки конструкция изменилась; сначала откатите более позднюю операцию");
            }
            if(examined>=512)throw new IllegalStateException("Слишком большая последующая история");
        }
    }
    public static void preview(CommandSourceStack source,UUID id,long entry){
        var player=source.getPlayer();if(player==null)return;
        if(!CreateCartAudit.enabled()||entry<=0||entry==Long.MAX_VALUE){source.sendFailure(Component.literal("Logger не авторизован или ID записи некорректен"));return;}
        PREVIEWS.remove(player.getUUID());long token=++sequence;REQUESTS.put(player.getUUID(),token);long request=generation;var storage=LoggerRuntime.storage(source.getLevel());
        CartRollbackCoordinator.query(source,()->{
            try{var plan=read(storage,id,entry);source.getServer().execute(()->{
                if(request!=generation||!LoggerConfig.isEnabled()||!Objects.equals(REQUESTS.get(player.getUUID()),token))return;REQUESTS.remove(player.getUUID());
                PREVIEWS.put(player.getUUID(),new Preview(id,entry,System.currentTimeMillis()+120000));
                source.sendSystemMessage(Component.literal("Разборка "+plan.operation()+": конструкций "+plan.bodies().size()+", блоков мира "+plan.world().size()+", дропов/клея "+plan.effects().size()+". Подтвердить: /log cart confirm-structure (2 минуты)."));
            });}catch(Exception failure){source.getServer().execute(()->{if(!Objects.equals(REQUESTS.get(player.getUUID()),token))return;REQUESTS.remove(player.getUUID());error(source,failure);});}
        });
    }
    public static void confirm(CommandSourceStack source){
        var player=source.getPlayer();if(player==null)return;var preview=PREVIEWS.remove(player.getUUID());
        if(preview==null||preview.expires<System.currentTimeMillis()){source.sendFailure(Component.literal("Предпросмотр разборки отсутствует или истёк"));return;}
        if(CartRollbackCoordinator.busy()||RollbackCoordinator.hasActiveRollback()||CartAuditContext.rollbackActive){source.sendFailure(Component.literal("Уже выполняется откат"));return;}
        if(!CreateCartAudit.enabled())return;
        preparing=true;preparingActor=player.getUUID();CartAuditContext.rollbackActive=true;long request=++generation;var history=LoggerRuntime.storage(source.getLevel());
        try{CartRollbackCoordinator.prepareTransferHistory(()->{
            try{var plan=read(history,preview.construction,preview.entry);var staged=plan.bodies().stream().map(body->new StagedContraptionRestore(CreateTrainRegistry.payload(plan.before(),body.before()))).toList();
                var trainPreparation=plan.before().contains("Train",10)?new CreateTrainRegistry.Prepared(plan.before().getCompound("Train")):null;
                source.getServer().execute(()->{if(request!=generation)return;preparing=false;preparingActor=null;active=new Job(source,plan,staged,preview.entry,history,trainPreparation);});
            }catch(Exception failure){source.getServer().execute(()->{if(request!=generation)return;preparing=false;preparingActor=null;release();error(source,failure);});}
        });}catch(RuntimeException failure){preparing=false;preparingActor=null;release();error(source,failure);}
    }
    public static void cancel(CommandSourceStack source){
        var player=source.getPlayer();if(player!=null){PREVIEWS.remove(player.getUUID());REQUESTS.remove(player.getUUID());
            if(preparing&&player.getUUID().equals(preparingActor)){generation++;preparing=false;preparingActor=null;release();}}
        if(active!=null&&Objects.equals(source.getEntity(),active.source.getEntity()))active.cancel("Отменено администратором");
    }
    public static void onTick(ServerTickEvent.Post event){
        var job=active;if(job==null||!event.hasTime())return;
        if(!LoggerConfig.isEnabled())job.cancel("Авторизация Logger отозвана");
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1);int operations=0;boolean previous=CartAuditContext.restoring(true);
        try{do{if(job.step(event.getServer())){active=null;release();return;}}while(++operations<16&&System.nanoTime()<deadline);}
        catch(RuntimeException failure){if(job.phase==8||job.phase==14)CreateCartAudit.failed("structural compensation; reservations retained",failure);else job.cancel(failure.getMessage());}
        finally{CartAuditContext.restoring(previous);}
    }
    public static void stop(){generation++;PREVIEWS.clear();REQUESTS.clear();boolean own=preparing||active!=null;preparing=false;preparingActor=null;
        if(active!=null){boolean previous=CartAuditContext.restoring(true);try{active.cancel("Сервер остановлен");while(!active.step(active.source.getServer())){}}catch(RuntimeException failure){CreateCartAudit.failed("structural shutdown compensation",failure);}finally{CartAuditContext.restoring(previous);}active=null;}
        if(own)release();
    }
    private static void release(){CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();CartAuditContext.rollbackActive=false;}
    private static void error(CommandSourceStack source,Exception failure){source.sendFailure(Component.literal("Откат разборки: "+failure.getMessage()));}
    private static ServerLevel level(MinecraftServer server,String dimension){var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(dimension)));if(level==null)throw new IllegalStateException("Измерение недоступно");return level;}
    private static CompoundTag stable(CompoundTag original){var tag=original.copy();for(var field:List.of("Pos","Motion","Rotation","FallDistance","Fire","Air","OnGround","PortalCooldown","TicksFrozen","Age","PickupDelay","Passengers"))tag.remove(field);return tag;}

    static final class Job {
        final CommandSourceStack source;final CreateStructuralRollbackPlan plan;final List<StagedContraptionRestore> staged;final long entry;final LogStorage history;
        final CreateTrainRegistry.Prepared trainPreparation;CreateTrainRegistry.Endpoint train;boolean trainReady;
        final List<CreateCartRegistry.Endpoint> carts=new ArrayList<>();int cartsPrepared,cartsApplied;final List<WorldEndpoint> world=new ArrayList<>();final List<Effect> effects=new ArrayList<>();
        int phase,index,applied,removed,spawned,linked,verified,compensationExtent;String failure;boolean committed;
        Job(CommandSourceStack source,CreateStructuralRollbackPlan plan,List<StagedContraptionRestore> staged,long entry){this(source,plan,staged,entry,null);}
        Job(CommandSourceStack source,CreateStructuralRollbackPlan plan,List<StagedContraptionRestore> staged,long entry,LogStorage history){this(source,plan,staged,entry,history,null);}
        Job(CommandSourceStack source,CreateStructuralRollbackPlan plan,List<StagedContraptionRestore> staged,long entry,LogStorage history,CreateTrainRegistry.Prepared prepared){trainPreparation=prepared!=null?prepared:plan.before().contains("Train",10)?new CreateTrainRegistry.Prepared(plan.before().getCompound("Train")):null;this.source=source;this.plan=plan;this.staged=staged;this.entry=entry;this.history=history;}
        void cancel(String reason){if(!committed&&phase!=8&&phase!=14){failure=reason;compensationExtent=applied;phase=8;}}
        boolean step(MinecraftServer server){
            if(phase==0){
                if(cartsPrepared<plan.before().getList("Carts",10).size()){int i=cartsPrepared++;carts.add(new CreateCartRegistry.Endpoint(server,plan.before().getList("Carts",10).getCompound(i),plan.after().getList("Carts",10).getCompound(i)));return false;}
                if(train==null&&plan.before().contains("Train",10)){train=new CreateTrainRegistry.Endpoint(server,plan.before().getCompound("Train"),plan.after().getCompound("Train"),trainPreparation);return false;}
                if(index<plan.world().size()){world.add(new WorldEndpoint(server,plan.world().get(index++)));return false;}
                index=0;for(var id:plan.constructions())CartRestoreLocks.construction(id);phase=1;return false;
            }
            if(phase==1){
                if(index<plan.bodies().size()){var body=plan.bodies().get(index++);var tag=body.before();var level=level(server,tag.getString("Dimension"));var pos=tag.getCompound("Entity").getList("Pos",6);
                    if(pos.size()!=3||!level.hasChunkAt(BlockPos.containing(pos.getDouble(0),pos.getDouble(1),pos.getDouble(2))))throw new IllegalStateException("Целевой чанк конструкции не загружен");
                    absent(server,tag.getUUID("StorageEntity"));CartRestoreLocks.keepLoaded(level,BlockPos.containing(pos.getDouble(0),pos.getDouble(1),pos.getDouble(2)));CartAuditContext.LOCKED_ENTITIES.add(tag.getUUID("StorageEntity"));return false;}
                if(effects.size()<plan.effects().size()){effects.add(new Effect(server,plan.effects().get(effects.size())));return false;}
                index=0;if(history==null){phase=2;return false;}phase=12;
                CartRollbackCoordinator.prepareTransferHistory(()->{try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);if(!AsyncLogProcessor.awaitIdle(deadline)||!history.awaitVisible(deadline))throw new IllegalStateException("Журнал ещё не виден в БД");verifyLatest(history,plan,entry);
                    server.execute(()->{if(active==this&&phase==12)phase=2;});}catch(Exception failure){server.execute(()->{if(active==this&&phase==12)cancel(failure.getMessage());});}});return false;
            }
            if(phase==12)return false;
            if(phase==2){if(train!=null&&!trainReady){trainReady=train.stage();return false;}if(index<staged.size()){
                if(train!=null)train.bind(staged.get(index),plan.bodies().get(index).before().getCompound("Entity"));
                if(staged.get(index).step(level(server,plan.bodies().get(index).before().getString("Dimension")))){
                    var body=plan.bodies().get(index).before();var entity=staged.get(index).entity;
                    if(!entity.getUUID().equals(body.getUUID("StorageEntity"))||!CreateContraptionSources.id(entity).equals(body.getUUID("Construction"))||!CreateContraptionSources.kind(entity).name().equals(body.getString("ConstructionKind")))throw new IllegalStateException("Идентичность payload не совпадает с адресом истории");if(train!=null&&entity instanceof com.simibubi.create.content.trains.entity.CarriageContraptionEntity carriage){((CreateOrientationAccess)carriage).avilixlogger$orientation(body.getCompound("Orientation"));train.bodyReady(carriage);}index++;
                }return false;
            }index=0;phase=3;return false;}
            if(phase==3){
                if(index<world.size()){world.get(index++).verify(false);return false;}
                if(verified<effects.size()){effects.get(verified++).verify();return false;}
                if(!carts.isEmpty()){CartRestoreLocks.resource(CreateCartRegistry.MUTATION);for(var cart:carts)cart.checkpoint();}
                if(train!=null){for(var body:plan.bodies())if(body.before().getString("ConstructionKind").equals("TRAIN")){
                    var tag=body.before();var bounds=tag.getList("Bounds",6);var box=new net.minecraft.world.phys.AABB(bounds.getDouble(0),bounds.getDouble(1),bounds.getDouble(2),bounds.getDouble(3),bounds.getDouble(4),bounds.getDouble(5));var dimension=level(server,tag.getString("Dimension"));
                    for(var other:dimension.getEntitiesOfClass(AbstractContraptionEntity.class,box.inflate(.01)))if(plan.bodies().stream().noneMatch(captured->captured.before().getUUID("StorageEntity").equals(other.getUUID())))throw new IllegalStateException("Место вагона занято другой конструкцией");
                }train.freeze();}index=0;phase=4;return false;
            }
            if(train!=null&&(phase>=4&&phase<=7||phase==13))train.unchanged();
            if(phase==4){if(applied<world.size()){var endpoint=world.get(applied);endpoint.verify(false);applied++;endpoint.set(true);return false;}phase=5;return false;}
            if(phase==5){if(removed<effects.size()){var effect=effects.get(removed);effect.verify();removed++;effect.remove();return false;}phase=6;return false;}
            if(phase==6){
                if(spawned<staged.size()){var entity=staged.get(spawned).entity;absent(server,entity.getUUID());spawned++;if(!((ServerLevel)entity.level()).addFreshEntity(entity))throw new IllegalStateException("Мир отклонил конструкцию");CartStorageAudit.bind(entity);return false;}
                if(linked<staged.size()){int i=linked++;var entity=staged.get(i).entity;var before=plan.bodies().get(i).before();
                    for(var raw:before.getList("Links",10)){var link=(CompoundTag)raw;if(link.getString("Relation").equals("vehicle")){var parent=staged.stream().map(stage->(Entity)stage.entity).filter(e->e.getUUID().equals(link.getUUID("Entity"))).findFirst().orElseGet(()->carts.stream().map(endpoint->(Entity)endpoint.cart).filter(e->e.getUUID().equals(link.getUUID("Entity"))).findFirst().orElseThrow());if(!entity.startRiding(parent,true))throw new IllegalStateException("Вложенная связь не восстановлена");}}
                    if(entity instanceof ControlledContraptionEntity controlled){var saved=before.getCompound("Entity");var position=saved.getList("Pos",6);var controllerPos=NBTHelper.readBlockPos(saved,"ControllerRelative").offset(BlockPos.containing(position.getDouble(0),position.getDouble(1),position.getDouble(2)));
                        var controller=entity.level().getBlockEntity(controllerPos);if(!(controller instanceof IControlContraption control)||!(controller instanceof CreateControllerAccess))throw new IllegalStateException("Контроллер не поддерживает привязку");control.attach(controlled);if(!control.isAttachedTo(controlled))throw new IllegalStateException("Контроллер отклонил конструкцию");}
                    if(entity instanceof OrientedContraptionEntity oriented&&CreateCartAudit.state(oriented)!=null)CreateCartAudit.attach(oriented);
                    if(entity instanceof CreateOrientationAccess orientation&&before.contains("Orientation",10))orientation.avilixlogger$orientation(before.getCompound("Orientation"));
                    return false;
                }if(cartsApplied<carts.size()){var cart=carts.get(cartsApplied++);cart.set(true);return false;}if(train!=null)train.install();index=0;phase=13;return false;
            }
            // Native replacement/attachment can dirty a neighbour restored earlier. Settle runtime
            // only after every body and block is in place, keeping the work incremental.
            if(phase==13){if(index<world.size()){world.get(index++).settle(true);return false;}index=0;phase=7;return false;}
            if(phase==7){
                if(index<world.size()){world.get(index++).verify(true);return false;}
                if(index-world.size()<staged.size()){int i=index++-world.size();var entity=staged.get(i).entity;
                    if(entity instanceof ControlledContraptionEntity){var saved=plan.bodies().get(i).before().getCompound("Entity");var position=saved.getList("Pos",6);var pos=NBTHelper.readBlockPos(saved,"ControllerRelative").offset(BlockPos.containing(position.getDouble(0),position.getDouble(1),position.getDouble(2)));
                        if(!(entity.level().getBlockEntity(pos) instanceof IControlContraption controller)||!controller.isAttachedTo(entity))throw new IllegalStateException("Привязка участника к контроллеру потеряна");}
                    var expectedLinks=plan.bodies().get(i).before().getList("Links",10);var actualLinks=CreateLifecycleAudit.links(entity);
                    if(actualLinks.size()!=expectedLinks.size()||!actualLinks.containsAll(expectedLinks))throw new IllegalStateException("Связи восстановленной конструкции отличаются от снимка");
                    if((train!=null&&entity instanceof com.simibubi.create.content.trains.entity.CarriageContraptionEntity||!carts.isEmpty()&&entity instanceof CreateOrientationAccess)&&!((CreateOrientationAccess)entity).avilixlogger$orientation().equals(plan.bodies().get(i).before().getCompound("Orientation")))throw new IllegalStateException("Поворот вагона отличается от снимка");
                    if(entity.isRemoved()||!stable(CreateCartAudit.save(entity)).equals(stable(plan.bodies().get(i).before().getCompound("Entity"))))throw new IllegalStateException("Payload восстановленной конструкции отличается от снимка");return false;}
                for(var cart:carts)cart.verify(true,true);if(train!=null)train.verify();committed=true;if(train!=null)try{train.publish();}catch(RuntimeException problem){CreateCartAudit.failed("committed train client sync",problem);}receipt(server);return true;
            }
            if(phase==8){
                if(train!=null)train.compensate();
                if(spawned>0){var entity=staged.get(--spawned).entity;if(entity!=null&&!entity.isRemoved())((CartEntityAccess)entity).avilixlogger$discardForRollback();return false;}
                if(cartsApplied>0){var cart=carts.get(cartsApplied-1);cart.set(false);cart.verify(false,true);cartsApplied--;return false;}
                if(removed>0){effects.get(removed-1).restore();removed--;return false;}
                if(applied>0){var endpoint=world.get(applied-1);endpoint.clearController();endpoint.set(false);applied--;return false;}
                index=0;phase=14;return false;
            }
            if(phase==14){
                if(compensationExtent>0&&index<world.size()){world.get(index++).settle(false);return false;}
                source.sendFailure(Component.literal("Откат разборки остановлен: "+failure+". Применённые изменения возвращены."));return true;
            }
            throw new IllegalStateException("Неверная фаза восстановления");
        }
        private void receipt(MinecraftServer server){boolean previous=CartAuditContext.restoring(false);
            try{CartStorageAudit.closeLockedMenus(server);for(var id:plan.constructions()){
                var body=plan.bodies().stream().map(CreateStructuralRollbackPlan.Body::before).filter(tag->id.equals(tag.getUUID("Construction"))).findFirst().orElseGet(()->{var cart=plan.before().getList("Carts",10).stream().map(raw->(CompoundTag)raw).filter(tag->tag.hasUUID("Construction")&&id.equals(tag.getUUID("Construction"))).findFirst().orElseThrow();var tag=new CompoundTag();tag.putString("Dimension",cart.getString("Dimension"));tag.putString("ConstructionKind","MINECART");tag.putUUID("StorageEntity",cart.getUUID("ConstructionEntity"));return tag;});var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.CART_ROLLBACK;row.dim=body.getString("Dimension");row.entityUuid=body.getUUID("StorageEntity");
                row.actorUuid=source.getPlayer()==null?null:source.getPlayer().getUUID();row.actorName=source.getTextName();row.source=CreateContraptionKind.valueOf(body.getString("ConstructionKind")).source(id,"structural_rollback");row.extra="operation="+plan.operation()+"; entry="+entry;
                row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,plan.after());row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,plan.before());LogIdGenerator.ensure(row);LoggerRuntime.storage(source.getLevel()).append(row);
            }source.sendSystemMessage(Component.literal("Разборка "+plan.operation()+" восстановлена: конструкций "+staged.size()+", блоков мира "+world.size()));}
            catch(RuntimeException failure){CreateCartAudit.failed("committed structural receipt",failure);}finally{CartAuditContext.restoring(previous);}
        }
        private static void absent(MinecraftServer server,UUID id){for(var level:server.getAllLevels())if(level.getEntity(id)!=null)throw new IllegalStateException("Сущность конструкции уже присутствует: "+id);}
    }
    private static final class WorldEndpoint {
        final ServerLevel level;final BlockPos pos;final CreateStructuralRollbackPlan.World undo;
        WorldEndpoint(MinecraftServer server,CreateStructuralRollbackPlan.World undo){this.undo=undo;var tag=undo.after();level=level(server,tag.getString("Dimension"));pos=BlockPos.of(tag.getLong("Pos"));verify(false);
            for(var raw:undo.before().getList("KineticNetworks",10))CreateKineticRegistry.reserve(level,(CompoundTag)raw);for(var raw:undo.after().getList("KineticNetworks",10))CreateKineticRegistry.reserve(level,(CompoundTag)raw);
            if(undo.before().contains("ElevatorColumn",10)){CreateElevatorRegistry.reserve(level,undo.before().getCompound("ElevatorColumn"));CreateElevatorRegistry.reserve(level,undo.after().getCompound("ElevatorColumn"));}
            CartRestoreLocks.keepLoaded(level,pos);CartRestoreLocks.block(level,pos);for(var direction:Direction.values())CartRestoreLocks.block(level,pos.relative(direction));CartRollbackCoordinator.reserveHandlers(level,pos,true);
            if(tag.getBoolean("Controller")&&!tag.getBoolean("ControllerRemoved")&&(!(level.getBlockEntity(pos) instanceof CreateControllerAccess controller)||controller.avilixlogger$controlled().stream().anyMatch(Entity::isAlive)))throw new IllegalStateException("Контроллер заменён или уже содержит конструкцию");
        }
        void verify(boolean before){var expected=before?undo.before():undo.after();var actual=CreateStructuralAudit.world(level,pos,expected);actual.remove("Controller");var copy=expected.copy();copy.remove("Controller");copy.remove("ControllerRemoved");if(!actual.equals(copy))throw new IllegalStateException("Блок, NBT или груз изменены: "+pos);}
        void clearController(){if(undo.after().getBoolean("Controller")&&level.getBlockEntity(pos) instanceof CreateControllerAccess controller)controller.avilixlogger$clearControlled();}
        void set(boolean before){var tag=before?undo.before():undo.after();var state=NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK),tag.getCompound("State"));
            if(!NbtUtils.writeBlockState(state).equals(tag.getCompound("State")))throw new IllegalStateException("Состояние блока не прочитано полностью");
            CartRollbackCoordinator.apply(level,pos,state,tag.contains("BE",10)?tag.getCompound("BE"):null,tag.contains("Slots",10)?tag.getCompound("Slots"):null);
            settle(before);
        }
        void settle(boolean before){var tag=before?undo.before():undo.after();
            if(tag.contains("Runtime",10)){if(!(level.getBlockEntity(pos) instanceof CreateControllerAccess controller))throw new IllegalStateException("Нет runtime контроллера");controller.avilixlogger$runtime(tag.getCompound("Runtime"));if(level.getBlockEntity(pos) instanceof CreateControllerRuntimeAccess extra)extra.avilixlogger$extraRuntime(tag.getCompound("Runtime").getCompound("Extra"));}
            if(tag.contains("KineticRuntime",10)){if(!(level.getBlockEntity(pos) instanceof CreateKineticAccess kinetic))throw new IllegalStateException("Нет runtime кинетического блока");kinetic.avilixlogger$kineticRuntime(tag.getCompound("KineticRuntime"));}
            for(var raw:tag.getList("KineticNetworks",10))CreateKineticRegistry.restore(level,(CompoundTag)raw);
            if(tag.contains("ElevatorColumn",10))CreateElevatorRegistry.restore(level,tag.getCompound("ElevatorColumn"));verify(before);
        }
    }
    private static final class Effect {
        final ServerLevel level;final CompoundTag snapshot;final UUID id;Entity entity;
        Effect(MinecraftServer server,CompoundTag snapshot){this.snapshot=snapshot;id=snapshot.getUUID("Entity");level=level(server,snapshot.getString("Dimension"));entity=level.getEntity(id);verify();CartRestoreLocks.keepLoaded(level,entity.blockPosition());CartAuditContext.LOCKED_ENTITIES.add(id);}
        void verify(){if(entity==null||entity.isRemoved()||level.getEntity(id)!=entity||!level.hasChunkAt(entity.blockPosition())||!stable(CreateCartAudit.save(entity)).equals(stable(snapshot.getCompound("NBT"))))throw new IllegalStateException("Дроп/клей убран, выгружен или изменён: "+id);}
        void remove(){entity.discard();if(level.getEntity(id)!=null)throw new IllegalStateException("Мир не удалил дроп/клей");}
        void restore(){if(!entity.isRemoved())return;if(level.getEntity(id)!=null)throw new IllegalStateException("UUID дропа занят при компенсации");entity=EntityType.loadEntityRecursive(snapshot.getCompound("NBT").copy(),level,e->e);if(entity==null||!level.addFreshEntity(entity))throw new IllegalStateException("Дроп/клей не возвращён при компенсации");verify();}
    }
}

package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import com.roften.avilixlogger.LoggerConfig;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.fluids.tank.storage.FluidTankMountedStorage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One exchange across all constructions and world endpoints, applied and compensated per tick. */
public final class CreateTransferRollbackCoordinator {
    private record Preview(CreateTransferRollbackPlan plan,long expires,long entry) {}
    private static final Map<UUID,Preview> PREVIEWS=new HashMap<>();
    private static final Map<UUID,Long> REQUESTS=new HashMap<>();
    private static long requestSequence;
    private static Job active;
    private static long generation;
    private CreateTransferRollbackCoordinator() {}
    public static boolean busy(){return active!=null;}
    public static void preview(CommandSourceStack source,UUID construction,long entry){
        var player=source.getPlayer();if(player==null)return;
        if(!CreateCartAudit.enabled()){source.sendFailure(Component.literal("Logger не авторизован или интеграция Create отключена"));return;}
        if(entry<=0||entry==Long.MAX_VALUE){source.sendFailure(Component.literal("Некорректный ID записи"));return;}
        PREVIEWS.remove(player.getUUID());long token=++requestSequence;REQUESTS.put(player.getUUID(),token);
        long request=generation;var storage=LoggerRuntime.storage(source.getLevel());
        CartRollbackCoordinator.query(source,()->{
            try{
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                if(!AsyncLogProcessor.awaitIdle(deadline)||!storage.awaitVisible(deadline))throw new IllegalStateException("Журнал ещё не виден в БД");
                var query=new LogQuery();query.dim="*";query.constructionId=construction;query.requireDetails=true;query.afterId=entry-1;query.beforeId=entry+1;query.untilTs=Long.MAX_VALUE;query.limit=1;
                var found=storage.queryReverse(query);
                if(found.size()!=1||found.getFirst().id!=entry||!Set.of(ActionType.CART_TRANSFER,ActionType.CREATE_DEPLOYER_ACTION).contains(found.getFirst().type)
                        ||!construction.equals(CreateContraptionKind.id(found.getFirst().source)))throw new IllegalStateException("Связанная передача не найдена");
                var row=found.getFirst();var plan=CreateTransferRollbackPlan.prepare(NbtSerde.fromSnbt(row.beBefore),NbtSerde.fromSnbt(row.beAfter));
                verifyLatest(storage,plan,entry);
                source.getServer().execute(()->{if(request!=generation||!LoggerConfig.isEnabled()||!Objects.equals(REQUESTS.get(player.getUUID()),token))return;
                    REQUESTS.remove(player.getUUID());
                    PREVIEWS.put(player.getUUID(),new Preview(plan,System.currentTimeMillis()+120000,entry));
                    source.sendSystemMessage(Component.literal("Передача "+plan.transfer()+": конструкций "+plan.constructions().size()+", слотов груза "+plan.cargo().size()+", внешних слотов "+plan.external().size()+", предметов мира "+plan.world().size()+", creative источников/поглотителей "+(plan.creative().size()+plan.worldCreative().size())+", рук активатора "+plan.hands().size()+", блоков "+plan.blocks().size()+", целей-сущностей "+plan.targets().size()+". Подтвердить: /log cart confirm-transfer (2 минуты)."));});
            }catch(Exception failure){source.getServer().execute(()->{if(!Objects.equals(REQUESTS.get(player.getUUID()),token))return;REQUESTS.remove(player.getUUID());source.sendFailure(Component.literal("Откат передачи: "+failure.getMessage()));});}
        });
    }
    private static void verifyLatest(LogStorage storage,CreateTransferRollbackPlan plan,long entry){
        for(UUID id:plan.constructions()){
            var query=new LogQuery();query.dim="*";query.constructionId=id;query.requireDetails=true;query.beforeId=Long.MAX_VALUE;query.untilTs=Long.MAX_VALUE;query.limit=512;
            query.types=EnumSet.of(ActionType.CART_CONTENT_CHANGE,ActionType.CART_TRANSFER,ActionType.CART_STRUCTURE_CHANGE,ActionType.CART_ROLLBACK,
                    ActionType.CART_ASSEMBLE,ActionType.CART_DISASSEMBLE,ActionType.CART_PACK,ActionType.CART_PLACE,ActionType.CART_REMOVE,
                    ActionType.CART_LOAD,ActionType.CART_UNLOAD,ActionType.CART_STATUS,ActionType.CREATE_DEPLOYER_ACTION);
            var rows=storage.queryReverse(query);int examined=0;
            for(var row:rows){if(row.id<=entry)break;examined++;
                if(!id.equals(CreateContraptionKind.id(row.source)))throw new IllegalStateException("БД вернула чужую историю конструкции");
                var details=NbtSerde.fromSnbt(row.beAfter);
                if(row.type!=ActionType.CART_CONTENT_CHANGE&&row.type!=ActionType.CART_TRANSFER&&row.type!=ActionType.CREATE_DEPLOYER_ACTION
                        ||details==null||!details.hasUUID("Transfer")||!plan.transfer().equals(details.getUUID("Transfer")))
                    throw new IllegalStateException("После передачи конструкция уже изменилась; сначала откатите более поздние операции");
            }
            if(examined>=512)throw new IllegalStateException("Слишком большая последующая история передачи");
        }
    }
    public static void confirm(CommandSourceStack source){
        var player=source.getPlayer();if(player==null)return;
        var preview=PREVIEWS.remove(player.getUUID());
        if(preview==null||preview.expires<System.currentTimeMillis()){source.sendFailure(Component.literal("Предпросмотр передачи отсутствует или истёк"));return;}
        if(CartRollbackCoordinator.busy()||RollbackCoordinator.hasActiveRollback()||CartAuditContext.rollbackActive){source.sendFailure(Component.literal("Уже выполняется откат"));return;}
        if(!CreateCartAudit.enabled())return;
        active=new Job(source,preview.plan,preview.entry,LoggerRuntime.storage(source.getLevel()));CartAuditContext.rollbackActive=true;
    }
    public static void onTick(ServerTickEvent.Post event){
        var job=active;if(job==null||!event.hasTime())return;
        if(!LoggerConfig.isEnabled())job.cancel("Авторизация Logger отозвана");
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1);int operations=0;
        boolean previous=CartAuditContext.restoring(true);
        try{
            do{if(job.step(event.getServer())){active=null;release();return;}}while(++operations<16&&System.nanoTime()<deadline);
        }catch(Exception failure){
            if(job.phase==4){CreateCartAudit.failed("joint transfer compensation; reservations retained",failure);}
            else job.cancel(failure.getMessage());
        }finally{CartAuditContext.restoring(previous);}
    }
    public static void cancel(CommandSourceStack source){if(source.getPlayer()!=null){PREVIEWS.remove(source.getPlayer().getUUID());REQUESTS.remove(source.getPlayer().getUUID());}if(active!=null&&Objects.equals(source.getEntity(),active.source.getEntity()))active.cancel("Отменено администратором");}
    public static void invalidated(net.minecraft.world.entity.Entity entity){
        if(active!=null&&!CartAuditContext.restoring()&&CartAuditContext.LOCKED_ENTITIES.contains(entity.getUUID()))active.cancel("Связанная сущность удалена или перемещена");
    }
    public static void stop(){
        generation++;PREVIEWS.clear();REQUESTS.clear();var job=active;
        if(job!=null){boolean previous=CartAuditContext.restoring(true);try{job.cancel("Сервер остановлен");while(!job.step(job.source.getServer())){}}
            catch(Exception failure){CreateCartAudit.failed("joint transfer shutdown compensation",failure);}finally{CartAuditContext.restoring(previous);}}
        active=null;if(job!=null)release();
    }
    private static void release(){CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();CartAuditContext.rollbackActive=false;}

    /** Package-visible so the dev server can exercise the same transaction without AuthCore bypass. */
    static final class Job {
        final CommandSourceStack source;final CreateTransferRollbackPlan plan;final long entry;
        final LogStorage history;final List<Endpoint> endpoints=new ArrayList<>();int phase,index,applied,verify;String failure;boolean committed;
        Job(CommandSourceStack source,CreateTransferRollbackPlan plan,long entry){this(source,plan,entry,null);}
        private Job(CommandSourceStack source,CreateTransferRollbackPlan plan,long entry,LogStorage history){this.source=source;this.plan=plan;this.entry=entry;this.history=history;}
        void cancel(String reason){if(!committed&&phase!=4){failure=reason;phase=4;}}
        boolean step(MinecraftServer server){
            if(phase==0){
                if(index<plan.cargo().size()){endpoints.add(new CargoEndpoint(server,plan.cargo().get(index++)));return false;}
                int external=index-plan.cargo().size();
                if(external<plan.external().size()){
                    var undo=plan.external().get(external);boolean item=undo.address().getString("Kind").equals("item");
                    var slot=new CartRollbackCoordinator.AutomationSlot(server,new CartRollbackCoordinator.AutomationData(undo,
                            item?CartRollbackCoordinator.readRecipientItem(undo.before(),server.registryAccess()):CartRollbackCoordinator.readAutomationFluid(undo.before(),server.registryAccess()),
                            item?CartRollbackCoordinator.readRecipientItem(undo.after(),server.registryAccess()):CartRollbackCoordinator.readAutomationFluid(undo.after(),server.registryAccess())));
                    endpoints.add(new Endpoint(){public void verifyAfter(){slot.verify(slot.data.after());}public void apply(){slot.applied=true;slot.set(slot.data.before());}public void verifyBefore(){slot.verify(slot.data.before());}public void compensate(){slot.compensate();}});index++;return false;
                }
                int world=external-plan.external().size();
                if(world<plan.world().size()){
                    var restore=new CreateWorldItemRestore(server,plan.world().get(world));
                    endpoints.add(new Endpoint(){public void verifyAfter(){restore.verifyAfter();}public void apply(){restore.apply();}public void verifyBefore(){restore.verifyBefore();}public void compensate(){restore.compensate();}});index++;return false;
                }
                int hands=world-plan.world().size();
                if(hands<plan.hands().size()){endpoints.add(CreateDeployerRestore.hand(server,plan.hands().get(hands)));index++;return false;}
                int blocks=hands-plan.hands().size();
                if(blocks<plan.blocks().size()){endpoints.add(CreateDeployerRestore.block(server,plan.blocks().get(blocks)));index++;return false;}
                int targets=blocks-plan.blocks().size();
                if(targets<plan.targets().size()){endpoints.add(CreateDeployerRestore.target(server,plan.targets().get(targets),plan.hands().getFirst().after()));index++;return false;}
                int creative=targets-plan.targets().size();
                if(creative<plan.creative().size()){endpoints.add(new CreateCreativeRestore(server,plan.creative().get(creative)));index++;return false;}
                int worldCreative=creative-plan.creative().size();
                if(worldCreative<plan.worldCreative().size()){endpoints.add(new CreateWorldCreativeRestore(server,plan.worldCreative().get(worldCreative)));index++;return false;}
                if(endpoints.isEmpty())throw new IllegalStateException("Передача не содержит изменений");
                index=0;
                if(history==null){phase=1;return false;}
                // All live endpoints are reserved before checking the durable history a second time.
                phase=14;
                CartRollbackCoordinator.prepareTransferHistory(()->{
                    try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                        if(!AsyncLogProcessor.awaitIdle(deadline)||!history.awaitVisible(deadline))throw new IllegalStateException("Журнал ещё не виден в БД");
                        verifyLatest(history,plan,entry);
                        server.execute(()->{if(active==this&&phase==14)phase=1;});
                    }catch(Exception failure){server.execute(()->{if(active==this&&phase==14)cancel(failure.getMessage());});}
                });return false;
            }
            if(phase==14)return false;
            if(phase==1){if(index<endpoints.size()){endpoints.get(index++).verifyAfter();return false;}phase=2;return false;}
            if(phase==2){if(applied<endpoints.size()){var endpoint=endpoints.get(applied);endpoint.verifyAfter();applied++;endpoint.apply();return false;}phase=3;return false;}
            if(phase==3){if(verify<endpoints.size()){endpoints.get(verify++).verifyBefore();return false;}committed=true;receipt(server);return true;}
            if(phase==4){if(applied>0){var endpoint=endpoints.get(applied-1);endpoint.compensate();applied--;return false;}
                source.sendFailure(Component.literal("Откат передачи остановлен: "+failure+". Применённые изменения возвращены."));return true;}
            throw new IllegalStateException("Неверная фаза передачи");
        }
        private void receipt(MinecraftServer server){
            var level=source.getLevel();boolean previous=CartAuditContext.restoring(false);
            try{
                CartStorageAudit.closeLockedMenus(server);
                for(UUID id:plan.constructions()){
                    var address=plan.hands().isEmpty()?plan.after().getList("Cargo",10).stream().map(tag->(CompoundTag)tag).filter(a->id.equals(a.getUUID("Construction"))).findFirst().orElseThrow():plan.hands().getFirst().after();
                    var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.CART_ROLLBACK;row.dim=address.getString("Dimension");row.entityUuid=address.getUUID("StorageEntity");
                    var participantLevel=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(row.dim)));
                    var participant=participantLevel==null?null:participantLevel.getEntity(row.entityUuid);
                    if(participant!=null){row.x=participant.blockPosition().getX();row.y=participant.blockPosition().getY();row.z=participant.blockPosition().getZ();row.entityType=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(participant.getType()).toString();}
                    row.actorUuid=source.getPlayer()==null?null:source.getPlayer().getUUID();row.actorName=source.getTextName();
                    row.source=CreateContraptionKind.valueOf(address.getString("ConstructionKind")).source(id,"transfer_rollback");row.extra="transfer="+plan.transfer()+"; entry="+entry;
                    row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,plan.after());row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,plan.before());LogIdGenerator.ensure(row);LoggerRuntime.storage(level).append(row);
                }
                source.sendSystemMessage(Component.literal("Передача "+plan.transfer()+" восстановлена согласованно: конструкций "+plan.constructions().size()));
            }catch(Exception failure){CreateCartAudit.failed("committed joint transfer receipt",failure);}finally{CartAuditContext.restoring(previous);}
        }
    }
    interface Endpoint {void verifyAfter();void apply();void verifyBefore();void compensate();}
    private static final class CargoEndpoint implements Endpoint {
        final CreateTransferRollbackPlan.CargoUndo undo;final AbstractContraptionEntity entity;final Object handler;final BlockPos local;final int slot;final boolean base,item;
        final Object before,after;boolean applied;
        CargoEndpoint(MinecraftServer server,CreateTransferRollbackPlan.CargoUndo undo){
            this.undo=undo;var address=undo.address();
            ServerLevel level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(address.getString("Dimension"))));
            if(level==null||!(level.getEntity(address.getUUID("StorageEntity")) instanceof AbstractContraptionEntity ce)||ce.isRemoved()||ce.getContraption()==null)throw new IllegalStateException("Конструкция выгружена или удалена");
            entity=ce;local=BlockPos.of(address.getLong("LocalPos"));base=address.getBoolean("BaseCart");item=address.getString("Kind").equals("item");slot=address.getInt(item?"Slot":"Tank");
            if(!CreateContraptionSources.id(ce).equals(address.getUUID("Construction"))||!CreateContraptionSources.kind(ce).name().equals(address.getString("ConstructionKind")))throw new IllegalStateException("ID или вид конструкции изменён");
            if(ce instanceof CarriageContraptionEntity carriage){
                if(carriage.getCarriage()==null||carriage.getCarriage().presentInMultipleDimensions()||!address.hasUUID("Carriage")
                        ||!address.getUUID("Carriage").equals(((ContraptionIdentity)carriage.getCarriage()).avilixlogger$identity()))throw new IllegalStateException("Вагон отсутствует, заменён или находится в портале");
            }
            handler=resolve();
            if(item){
                if(!(handler instanceof IItemHandlerModifiable writable))throw new IllegalStateException("Нет обратимого предметного хранилища");
                if(!supportedItem(handler))throw new IllegalStateException("Контракт модового хранилища ещё не проверен");
                before=CartRollbackCoordinator.readRecipientItem(undo.before(),server.registryAccess());after=CartRollbackCoordinator.readRecipientItem(undo.after(),server.registryAccess());
                if(slot<0||slot>=writable.getSlots()||((ItemStack)before).getCount()>writable.getSlotLimit(slot)||((ItemStack)after).getCount()>writable.getSlotLimit(slot))throw new IllegalStateException("Размеры предметного хранилища изменены");
            }else{
                if(handler==null||handler.getClass()!=FluidTankMountedStorage.class||slot!=0)throw new IllegalStateException("Нет обратимого бака Create");
                before=CartRollbackCoordinator.readAutomationFluid(undo.before(),server.registryAccess());after=CartRollbackCoordinator.readAutomationFluid(undo.after(),server.registryAccess());
                if(((FluidStack)before).getAmount()>((FluidTankMountedStorage)handler).getCapacity()||((FluidStack)after).getAmount()>((FluidTankMountedStorage)handler).getCapacity())throw new IllegalStateException("Ёмкость бака изменена");
            }
            verifyAfter();CartRestoreLocks.keepLoaded(level,ce.blockPosition());CartAuditContext.LOCKED_ENTITIES.add(ce.getUUID());CartRestoreLocks.construction(address.getUUID("Construction"));
            if(ce.getVehicle()!=null){CartAuditContext.LOCKED_ENTITIES.add(ce.getVehicle().getUUID());if(ce.getVehicle() instanceof net.minecraft.world.Container container)CartRestoreLocks.container(container);}
            CartRestoreLocks.handler(handler);CartStorageAudit.lockMenus(address.getUUID("Construction"),server);
        }
        private static boolean supportedItem(Object handler){
            String type=handler.getClass().getName();
            return Set.of("com.simibubi.create.api.contraption.storage.item.chest.ChestMountedStorage",
                    "com.simibubi.create.content.logistics.vault.ItemVaultMountedStorage",
                    "com.simibubi.create.content.contraptions.behaviour.dispenser.storage.DispenserMountedStorage",
                    "com.simibubi.create.content.logistics.depot.storage.DepotMountedStorage",
                    "com.simibubi.create.content.equipment.toolbox.ToolboxMountedStorage",
                    "com.simibubi.create.impl.contraption.storage.FallbackMountedStorage",
                    "net.neoforged.neoforge.items.wrapper.InvWrapper").contains(type);
        }
        private Object resolve(){return base&&entity.getVehicle() instanceof net.minecraft.world.Container container?new net.neoforged.neoforge.items.wrapper.InvWrapper(container)
                :item?entity.getContraption().getStorage().getAllItemStorages().get(local):entity.getContraption().getStorage().getFluids().storages.get(local);}
        private void verify(Object expected){
            if(entity.isRemoved()||entity.getContraption()==null||!CreateContraptionSources.id(entity).equals(undo.address().getUUID("Construction")))throw new IllegalStateException("Конструкция изменена во время передачи");
            Object current=resolve();if(!base&&current!=handler||current==null)throw new IllegalStateException("Хранилище заменено");
            if(base&&(!(current instanceof net.neoforged.neoforge.items.wrapper.InvWrapper wrapper)||((net.neoforged.neoforge.items.wrapper.InvWrapper)handler).getInv()!=wrapper.getInv()))throw new IllegalStateException("Базовая вагонетка заменена");
            if(item?!ItemStack.matches((ItemStack)expected,((IItemHandlerModifiable)current).getStackInSlot(slot)):!FluidStack.matches((FluidStack)expected,((FluidTankMountedStorage)current).getFluid()))throw new IllegalStateException("Содержимое участника передачи изменено");
        }
        public void verifyAfter(){verify(after);}
        private void set(Object value){
            if(item)((IItemHandlerModifiable)handler).setStackInSlot(slot,((ItemStack)value).copy());
            else{var tank=(FluidTankMountedStorage)handler;var fluid=(FluidStack)value;tank.drain(Integer.MAX_VALUE,FluidAction.EXECUTE);if(!fluid.isEmpty()&&tank.fill(fluid.copy(),FluidAction.EXECUTE)!=fluid.getAmount())throw new IllegalStateException("Бак отклонил восстановление");}
            if(base&&entity.getVehicle() instanceof net.minecraft.world.Container container)container.setChanged();verify(value);
        }
        public void apply(){applied=true;set(before);}
        public void verifyBefore(){verify(before);}
        public void compensate(){if(applied){set(after);applied=false;}}
    }
}

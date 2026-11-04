package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllAttachmentTypes;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.minecart.capability.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Graph-wide native controller transaction with bounded steps and cancellation compensation. */
public final class CreateCouplingRollbackCoordinator {
    private record Preview(UUID construction,long entry,long expires){}
    private static final Map<UUID,Preview> PREVIEWS=new HashMap<>();
    public static void expirePreviews(long now){PreviewExpiry.prune(PREVIEWS,now,Preview::expires);}
    private static final Map<UUID,Long> REQUESTS=new HashMap<>();
    private static long sequence,generation;private static boolean preparing;private static UUID preparingActor;private static Job active;
    private CreateCouplingRollbackCoordinator(){}
    public static boolean busy(){return preparing||active!=null;}
    private static CreateCouplingRollbackPlan read(LogStorage storage,UUID id,long entry)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        if(!AsyncLogProcessor.awaitIdle(deadline)||!storage.awaitVisible(deadline))throw new IllegalStateException("Журнал ещё не виден в БД");
        var query=new LogQuery();query.dim="*";query.constructionId=id;query.requireDetails=true;query.afterId=entry-1;query.beforeId=entry+1;query.untilTs=Long.MAX_VALUE;query.limit=1;
        var rows=storage.queryReverse(query);
        if(rows.size()!=1||rows.getFirst().id!=entry||rows.getFirst().type!=ActionType.CART_STATUS||!id.equals(CreateContraptionKind.id(rows.getFirst().source)))throw new IllegalStateException("Полная запись сцепки не найдена");
        var row=rows.getFirst();var plan=CreateCouplingRollbackPlan.prepare(NbtSerde.fromSnbt(row.beBefore),NbtSerde.fromSnbt(row.beAfter));
        if(!plan.constructions().contains(id))throw new IllegalStateException("Запись не относится к участнику сцепки");verifyLatest(storage,plan,entry);return plan;
    }
    static void verifyLatest(LogStorage storage,CreateCouplingRollbackPlan plan,long entry){
        for(var id:plan.constructions()){
            var query=new LogQuery();query.dim="*";query.constructionId=id;query.requireDetails=true;query.beforeId=Long.MAX_VALUE;query.untilTs=Long.MAX_VALUE;query.limit=512;int examined=0;
            for(var row:storage.queryReverse(query)){
                if(row.id<=entry)break;examined++;var tag=NbtSerde.fromSnbt(row.beAfter);
                if(!id.equals(CreateContraptionKind.id(row.source))||row.type!=ActionType.CART_STATUS||tag==null||!tag.getString("Kind").equals("coupling")||!tag.hasUUID("Operation")||!plan.operation().equals(tag.getUUID("Operation")))throw new IllegalStateException("Участник изменился после сцепки; сначала откатите более позднюю операцию");
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
                if(request!=generation||!LoggerConfig.isEnabled()||!Objects.equals(REQUESTS.get(player.getUUID()),token))return;REQUESTS.remove(player.getUUID());PREVIEWS.put(player.getUUID(),new Preview(id,entry,System.currentTimeMillis()+120000));
                source.sendSystemMessage(Component.literal("Сцепка "+plan.operation()+": вагонеток "+plan.carts().size()+". Подтвердить: /log cart confirm-coupling (2 минуты)."));
            });}catch(Exception failure){source.getServer().execute(()->{if(!Objects.equals(REQUESTS.get(player.getUUID()),token))return;REQUESTS.remove(player.getUUID());error(source,failure);});}
        });
    }
    public static void confirm(CommandSourceStack source){
        var player=source.getPlayer();if(player==null)return;var preview=PREVIEWS.remove(player.getUUID());
        if(preview==null||preview.expires<System.currentTimeMillis()){source.sendFailure(Component.literal("Предпросмотр сцепки отсутствует или истёк"));return;}
        if(CartRollbackCoordinator.busy()||RollbackCoordinator.hasActiveRollback()||CartAuditContext.rollbackActive){source.sendFailure(Component.literal("Уже выполняется откат"));return;}
        if(!CreateCartAudit.enabled())return;
        preparing=true;preparingActor=player.getUUID();CartAuditContext.rollbackActive=true;long request=++generation;var history=LoggerRuntime.storage(source.getLevel());
        try{CartRollbackCoordinator.prepareTransferHistory(()->{
            try{var plan=read(history,preview.construction,preview.entry);source.getServer().execute(()->{if(request!=generation)return;preparing=false;preparingActor=null;active=new Job(source,plan,preview.entry,history);});}
            catch(Exception failure){source.getServer().execute(()->{if(request!=generation)return;preparing=false;preparingActor=null;release();error(source,failure);});}
        });}catch(RuntimeException failure){preparing=false;preparingActor=null;release();error(source,failure);}
    }
    public static void cancel(CommandSourceStack source){
        var player=source.getPlayer();if(player!=null){PREVIEWS.remove(player.getUUID());REQUESTS.remove(player.getUUID());if(preparing&&player.getUUID().equals(preparingActor)){generation++;preparing=false;preparingActor=null;release();}}
        if(active!=null&&Objects.equals(source.getEntity(),active.source.getEntity()))active.cancel("Отменено администратором");
    }
    public static void onTick(ServerTickEvent.Post event){
        var job=active;if(job==null||!event.hasTime())return;if(!LoggerConfig.isEnabled())job.cancel("Авторизация Logger отозвана");
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1);int operations=0;boolean previous=CartAuditContext.restoring(true);
        try{do{if(job.step(event.getServer())){active=null;release();return;}}while(++operations<16&&System.nanoTime()<deadline);}
        catch(RuntimeException failure){if(job.phase==8)CreateCartAudit.failed("coupling compensation; reservations retained",failure);else job.cancel(failure.getMessage());}
        finally{CartAuditContext.restoring(previous);}
    }
    public static void stop(){generation++;PREVIEWS.clear();REQUESTS.clear();boolean own=busy();preparing=false;preparingActor=null;
        if(active!=null){boolean previous=CartAuditContext.restoring(true);try{active.cancel("Сервер остановлен");while(!active.step(active.source.getServer())){}}catch(RuntimeException failure){CreateCartAudit.failed("coupling shutdown compensation",failure);}finally{CartAuditContext.restoring(previous);}active=null;}
        if(own)release();
    }
    private static void release(){CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();CartAuditContext.rollbackActive=false;}
    private static void error(CommandSourceStack source,Exception failure){source.sendFailure(Component.literal("Откат сцепки: "+failure.getMessage()));}

    static final class Job {
        final CommandSourceStack source;final CreateCouplingRollbackPlan plan;final long entry;final LogStorage history;final List<Endpoint> endpoints=new ArrayList<>();
        int phase,index,applied;boolean committed;String failure;
        Job(CommandSourceStack source,CreateCouplingRollbackPlan plan,long entry){this(source,plan,entry,null);}
        Job(CommandSourceStack source,CreateCouplingRollbackPlan plan,long entry,LogStorage history){this.source=source;this.plan=plan;this.entry=entry;this.history=history;}
        void cancel(String reason){if(!committed){phase=8;failure=reason;}}
        boolean step(MinecraftServer server){
            if(phase==0){if(index<plan.carts().size()){endpoints.add(new Endpoint(server,plan.carts().get(index++)));return false;}
                for(var id:plan.constructions())CartRestoreLocks.construction(id);index=0;
                if(history==null){phase=1;return false;}phase=12;
                CartRollbackCoordinator.prepareTransferHistory(()->{try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);if(!AsyncLogProcessor.awaitIdle(deadline)||!history.awaitVisible(deadline))throw new IllegalStateException("Журнал ещё не виден в БД");verifyLatest(history,plan,entry);server.execute(()->{if(active==this&&phase==12)phase=1;});}
                    catch(Exception failure){server.execute(()->{if(active==this&&phase==12)cancel(failure.getMessage());});}});return false;}
            if(phase==12)return false;
            if(phase==1){if(index<endpoints.size()){endpoints.get(index++).verify(false);return false;}index=0;phase=2;return false;}
            if(phase==2){if(applied<endpoints.size()){var endpoint=endpoints.get(applied);endpoint.verify(false);applied++;endpoint.set(true);return false;}index=0;phase=3;return false;}
            if(phase==3){if(index<endpoints.size()){endpoints.get(index++).verify(true);return false;}committed=true;receipt();return true;}
            if(phase==8){if(applied>0){endpoints.get(applied-1).set(false);applied--;return false;}source.sendFailure(Component.literal("Откат сцепки остановлен: "+failure+". Изменения возвращены."));return true;}
            throw new IllegalStateException("Неверная фаза сцепки");
        }
        private void receipt(){boolean previous=CartAuditContext.restoring(false);
            try{for(var id:plan.constructions()){var endpoint=endpoints.stream().filter(e->id.equals(e.undo.before().hasUUID("Construction")?e.undo.before().getUUID("Construction"):null)).findFirst().orElseThrow();
                var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.CART_ROLLBACK;row.dim=endpoint.level.dimension().location().toString();row.entityUuid=endpoint.undo.before().getUUID("ConstructionEntity");row.actorUuid=source.getPlayer()==null?null:source.getPlayer().getUUID();row.actorName=source.getTextName();row.source=CreateContraptionKind.MINECART.source(id,"coupling_rollback");row.extra="operation="+plan.operation()+"; entry="+entry;
                row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,plan.after());row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,plan.before());LogIdGenerator.ensure(row);LoggerRuntime.storage(source.getLevel()).append(row);
            }source.sendSystemMessage(Component.literal("Сцепка "+plan.operation()+" восстановлена: вагонеток "+endpoints.size()));}
            catch(RuntimeException failure){CreateCartAudit.failed("committed coupling receipt",failure);}finally{CartAuditContext.restoring(previous);}
        }
    }
    private static final class Endpoint {
        final ServerLevel level;final AbstractMinecart cart;final MinecartController controller;final CreateCouplingRollbackPlan.Cart undo;
        Endpoint(MinecraftServer server,CreateCouplingRollbackPlan.Cart undo){this.undo=undo;var tag=undo.after();AbstractMinecart found=null;
            for(var world:server.getAllLevels()){var entity=world.getEntity(tag.getUUID("CartEntity"));if(entity instanceof AbstractMinecart candidate){if(found!=null)throw new IllegalStateException("Повторный UUID вагонетки");found=candidate;}}
            if(found==null)throw new IllegalStateException("Вагонетка отсутствует");cart=found;level=(ServerLevel)cart.level();controller=cart.getData(AllAttachmentTypes.MINECART_CONTROLLER);verify(false);
            CartRestoreLocks.keepLoaded(level,cart.blockPosition());CartAuditContext.LOCKED_ENTITIES.add(cart.getUUID());var passenger=CreateCartAudit.passenger(cart);if(passenger!=null)CartAuditContext.LOCKED_ENTITIES.add(passenger.getUUID());
        }
        void verify(boolean old){var tag=old?undo.before():undo.after();
            if(cart.isRemoved()||level.getEntity(cart.getUUID())!=cart||!level.hasChunkAt(cart.blockPosition())||controller!=cart.getData(AllAttachmentTypes.MINECART_CONTROLLER)||controller!=CapabilityMinecartController.getIfPresent(level,cart.getUUID())||!CreateCouplingAudit.data(controller).equals(tag)
                    ||CapabilityMinecartController.loadedMinecartsWithCoupling.get(level).contains(cart.getUUID())!=controller.isLeadingCoupling())throw new IllegalStateException("Вагонетка, пассажир или реестр сцепки изменены");
        }
        void set(boolean old){var tag=old?undo.before():undo.after();controller.deserializeNBT(level.registryAccess(),tag.getCompound("Native").copy());
            var passenger=CreateCartAudit.passenger(cart);if(passenger instanceof OrientedContraptionEntity oriented)oriented.setCouplingId(tag.hasUUID("CouplingId")?tag.getUUID("CouplingId"):null);
            CapabilityMinecartController.loadedMinecartsByUUID.get(level).put(cart.getUUID(),controller);var leading=CapabilityMinecartController.loadedMinecartsWithCoupling.get(level);if(controller.isLeadingCoupling())leading.add(cart.getUUID());else leading.remove(cart.getUUID());controller.sendData();verify(old);
        }
    }
}

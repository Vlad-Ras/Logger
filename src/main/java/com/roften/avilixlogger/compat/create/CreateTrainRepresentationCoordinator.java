package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** History and permissions use the same path from chat and GUI. One reserved recovery at a time. */
public final class CreateTrainRepresentationCoordinator {
    private record Preview(UUID train,long entry,long expires) {}
    private static final Map<UUID,Preview> PREVIEWS=new HashMap<>();
    private static final Map<UUID,Long> REQUESTS=new HashMap<>();
    private static long sequence,generation;private static UUID preparing;private static Active active;
    private static final class Active {
        final CommandSourceStack source;final Preview preview;final CompoundTag snapshot;final CreateTrainRepresentationRecovery.Job job;String failure;
        Active(CommandSourceStack source,Preview preview,CompoundTag snapshot,CreateTrainRepresentationRecovery.Job job){this.source=source;this.preview=preview;this.snapshot=snapshot;this.job=job;}
        void cancel(String reason){failure=reason;job.cancel();}
    }
    private CreateTrainRepresentationCoordinator() {}
    public static boolean busy(){return preparing!=null||active!=null;}
    public static void expirePreviews(long now){PreviewExpiry.prune(PREVIEWS,now,Preview::expires);}
    private static CompoundTag read(LogStorage history,UUID train,long entry) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        if(!AsyncLogProcessor.awaitIdle(deadline)||!history.awaitVisible(deadline))throw new IllegalStateException("Журнал ещё не виден в БД");
        var query=new LogQuery();query.dim="*";query.constructionId=train;query.requireDetails=true;query.afterId=entry-1;query.beforeId=Long.MAX_VALUE;query.untilTs=Long.MAX_VALUE;query.limit=2;
        var rows=history.queryReverse(query);
        if(rows.size()!=1||rows.getFirst().id!=entry||!train.equals(CreateContraptionKind.id(rows.getFirst().source)))throw new IllegalStateException("Запись не найдена или у поезда есть более поздние события");
        var row=rows.getFirst();if(row.type!=ActionType.CART_UNLOAD&&row.type!=ActionType.CART_REMOVE)throw new IllegalStateException("Нужна последняя запись выгрузки или удаления представления");
        var before=NbtSerde.fromSnbt(row.beBefore);
        if(before==null||before.getInt("Format")!=2||!before.getBoolean("TrainRepresentationsComplete")||!before.getString("ConstructionKind").equals("TRAIN")||!before.hasUUID("Construction")||!train.equals(before.getUUID("Construction")))throw new IllegalStateException("Нет полного снимка вагона");
        var snapshot=before.getCompound("TrainRepresentations");if(!train.equals(CreateTrainRepresentationState.prepare(snapshot).train()))throw new IllegalStateException("Снимок относится к другому поезду");return snapshot;
    }
    public static void preview(CommandSourceStack source,UUID train,long entry) {
        var player=source.getPlayer();if(player==null)return;
        if(!CreateCartAudit.enabled()||entry<=0||entry==Long.MAX_VALUE){source.sendFailure(Component.literal("Logger не авторизован или ID записи некорректен"));return;}
        var actor=player.getUUID();PREVIEWS.remove(actor);long token=++sequence;REQUESTS.put(actor,token);long request=generation;var history=LoggerRuntime.storage(source.getLevel());
        CartRollbackCoordinator.query(source,()->{
            try{var prepared=new CreateTrainRepresentationRecovery.Prepared(read(history,train,entry));source.getServer().execute(()->{
                if(request!=generation||!Objects.equals(REQUESTS.get(actor),token))return;REQUESTS.remove(actor);
                if(!CreateCartAudit.enabled()||source.getServer().getPlayerList().getPlayer(actor)!=player)return;
                PREVIEWS.put(actor,new Preview(train,entry,System.currentTimeMillis()+120000));
                source.sendSystemMessage(Component.literal("Вагон "+prepared.carriage+": проверено измерений "+prepared.bodies.size()+". Восстановятся отсутствующие представления при неизменных позициях и грузе. Загрузите целевые чанки. Подтвердить: /log cart confirm-representations (2 минуты)."));
            });}catch(Exception failure){source.getServer().execute(()->{if(!Objects.equals(REQUESTS.get(actor),token))return;REQUESTS.remove(actor);error(source,failure);});}
        });
    }
    public static void confirm(CommandSourceStack source) {
        var player=source.getPlayer();if(player==null)return;var preview=PREVIEWS.remove(player.getUUID());
        if(preview==null||preview.expires()<System.currentTimeMillis()){source.sendFailure(Component.literal("Предпросмотр представлений отсутствует или истёк"));return;}
        if(CartRollbackCoordinator.busy()||RollbackCoordinator.hasActiveRollback()||CartAuditContext.rollbackActive){source.sendFailure(Component.literal("Уже выполняется откат"));return;}
        if(!CreateCartAudit.enabled())return;
        preparing=player.getUUID();CartAuditContext.rollbackActive=true;long request=++generation;var history=LoggerRuntime.storage(source.getLevel());
        try{CartRollbackCoordinator.prepareTransferHistory(()->{
            try{var snapshot=read(history,preview.train(),preview.entry());var prepared=new CreateTrainRepresentationRecovery.Prepared(snapshot);
                source.getServer().execute(()->{
                    if(request!=generation)return;
                    boolean previous=CartAuditContext.restoring(true);
                    try{
                        if(!CreateCartAudit.enabled()||source.getServer().getPlayerList().getPlayer(player.getUUID())!=player)throw new IllegalStateException("Авторизация или игрок недоступны");
                        var job=new CreateTrainRepresentationRecovery.Job(source.getServer(),prepared);active=new Active(source,preview,snapshot,job);preparing=null;
                    }catch(RuntimeException failure){preparing=null;release();error(source,failure);}finally{CartAuditContext.restoring(previous);}
                });
            }catch(Exception failure){source.getServer().execute(()->{if(request!=generation)return;preparing=null;release();error(source,failure);});}
        });}catch(RuntimeException failure){preparing=null;release();error(source,failure);}
    }
    public static void logout(UUID actor){PREVIEWS.remove(actor);REQUESTS.remove(actor);if(actor.equals(preparing)){generation++;preparing=null;release();}if(active!=null&&active.source.getEntity()!=null&&actor.equals(active.source.getEntity().getUUID()))active.cancel("Игрок вышел");}
    public static void cancel(CommandSourceStack source) {
        var player=source.getPlayer();if(player==null)return;var id=player.getUUID();PREVIEWS.remove(id);REQUESTS.remove(id);
        if(id.equals(preparing)){generation++;preparing=null;release();}
        if(active!=null&&Objects.equals(active.source.getEntity(),source.getEntity()))active.cancel("Отменено администратором");
    }
    public static void onTick(ServerTickEvent.Post event) {
        var current=active;if(current==null||!event.hasTime())return;
        if(!CreateCartAudit.enabled())current.cancel("Авторизация Logger отозвана");
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1);int operations=0;boolean previous=CartAuditContext.restoring(true);
        try{
            do{if(current.job.step()){
                if(current.job.committed())receipt(current);else current.source.sendFailure(Component.literal("Восстановление представлений остановлено: "+current.failure+". Созданные представления удалены, исходный груз сохранён."));
                active=null;release();return;
            }}while(++operations<16&&System.nanoTime()<deadline);
        }catch(RuntimeException failure){current.cancel(failure.getMessage());CreateCartAudit.failed("train representation recovery/compensation",failure);}
        finally{CartAuditContext.restoring(previous);}
    }
    private static void receipt(Active current) {
        boolean previous=CartAuditContext.restoring(false);
        try{
            var entity=current.job.result();var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.CART_ROLLBACK;row.dim=entity.level().dimension().location().toString();row.x=entity.blockPosition().getX();row.y=entity.blockPosition().getY();row.z=entity.blockPosition().getZ();row.entityUuid=entity.getUUID();row.entityType="create:carriage_contraption";row.actorUuid=current.source.getPlayer()==null?null:current.source.getPlayer().getUUID();row.actorName=current.source.getTextName();row.source=CreateContraptionKind.TRAIN.source(current.preview.train(),"representation_recovery");row.extra="entry="+current.preview.entry()+"; representations="+current.job.count();
            row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,current.snapshot);row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,CreateTrainRepresentationState.capture(current.job.carriage,current.source.getLevel(),current.snapshot.getInt("Index")));LogIdGenerator.ensure(row);LoggerRuntime.storage(current.source.getLevel()).append(row);
            current.source.sendSystemMessage(Component.literal("Восстановлено представлений вагона: "+current.job.count()+". Общий груз сохранён."));
        }catch(RuntimeException failure){CreateCartAudit.failed("committed train representation receipt",failure);}finally{CartAuditContext.restoring(previous);}
    }
    public static void stop() {
        generation++;PREVIEWS.clear();REQUESTS.clear();boolean own=preparing!=null||active!=null;preparing=null;
        if(active!=null){boolean previous=CartAuditContext.restoring(true);try{active.cancel("Сервер остановлен");while(!active.job.step()){}active=null;}catch(RuntimeException failure){CreateCartAudit.failed("train representation shutdown compensation; reservations retained",failure);return;}finally{CartAuditContext.restoring(previous);}}
        if(own)release();
    }
    private static void release(){CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();CartAuditContext.rollbackActive=false;}
    private static void error(CommandSourceStack source,Exception failure){source.sendFailure(Component.literal("Представления поезда: "+failure.getMessage()));}
}

package com.roften.avilixlogger.compat.create;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import com.roften.avilixlogger.net.CartHistoryNetwork;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
/** Absent train recovery, not a rollback of its world actions. No forced chunk loads. */
public final class CreateTrainRecovery {
    private record Preview(CreateTrainLifecyclePlan plan,CreateTrainRegistry.Prepared prepared,long entry,long expires){}
    private static final Map<UUID,Preview> PREVIEWS=new HashMap<>();
    private static final Map<UUID,Long> REQUESTS=new HashMap<>();
    private static final Map<UUID,Map<UUID,java.lang.ref.WeakReference<CarriageContraptionEntity>>> LOADED=new HashMap<>();
    private static long sequence,generation;private static Job active;
    public static void expirePreviews(long now){PreviewExpiry.prune(PREVIEWS,now,Preview::expires);}
    private static boolean authorized(ServerPlayer p){return p!=null&&p.getServer()!=null&&CreateSupport.available()&&LoggerConfig.isEnabled()
            &&p.getServer().getPlayerList().getPlayer(p.getUUID())==p&&PermissionUtil.has(p.createCommandSourceStack(),"avilixlogger.command.rollback",2);}
    public static void preview(CommandSourceStack source,UUID id,long entry){
        var player=source.getPlayer();if(!authorized(player))return;long token=++sequence,request=generation;
        REQUESTS.put(player.getUUID(),token);PREVIEWS.remove(player.getUUID());var storage=LoggerRuntime.storage(source.getLevel());
        if(!CartRollbackCoordinator.query(source,()->{try{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            if(!AsyncLogProcessor.awaitIdle(deadline)||!storage.awaitVisible(deadline))throw new IllegalStateException("Журнал ещё не подтверждён");
            var q=new LogQuery();q.dim="*";q.constructionId=id;q.requireDetails=true;q.limit=1;q.sinceTs=0;q.untilTs=Long.MAX_VALUE;
            q.types=EnumSet.of(ActionType.CART_STATUS);q.sourcePrefix=CreateContraptionKind.TRAIN.source(id,"state_checkpoint");
            if(entry>0){if(entry==Long.MAX_VALUE)throw new IllegalStateException("Некорректный entry");q.afterId=entry-1;q.beforeId=entry+1;}
            var rows=storage.queryReverse(q);if(rows.size()!=1||entry>0&&rows.getFirst().id!=entry)throw new IllegalStateException("Контрольная точка поезда не найдена");
            var row=rows.getFirst();var plan=CreateTrainLifecyclePlan.prepare(NbtSerde.fromSnbt(row.beAfter));
            if(!id.equals(plan.id())||!row.source.equals(CreateContraptionKind.TRAIN.source(id,"state_checkpoint")))throw new IllegalStateException("Чужая контрольная точка");
            var preview=new Preview(plan,new CreateTrainRegistry.Prepared(plan.registry(),true),row.id,System.currentTimeMillis()+120000);
            source.getServer().execute(()->{if(request!=generation||!Objects.equals(REQUESTS.get(player.getUUID()),token))return;REQUESTS.remove(player.getUUID());
                try{if(!authorized(player))return;absent(source.getServer(),id);PREVIEWS.put(player.getUUID(),preview);
                    CartHistoryNetwork.confirm(source,"Вернуть отсутствующий поезд "+id+" из entry="+row.id+"? Вагонов: "+plan.carriages()+", представлений: "+plan.projections()+". Возвращаются состав, груз, расписание и положения. Выгруженные представления загрузит Create. Действия мира не откатываются; используется именно эта контрольная точка.","log cart confirm-train "+id+" "+row.id);
                }catch(Exception e){error(source,e);}});
        }catch(Exception e){source.getServer().execute(()->{if(!Objects.equals(REQUESTS.get(player.getUUID()),token))return;REQUESTS.remove(player.getUUID());error(source,e);});}}))REQUESTS.remove(player.getUUID());
    }
    public static void confirm(CommandSourceStack source,UUID id,long entry){
        var player=source.getPlayer();if(!authorized(player))return;var preview=PREVIEWS.get(player.getUUID());
        if(preview==null||!id.equals(preview.plan.id())||entry!=preview.entry||preview.expires<System.currentTimeMillis()){
            error(source,new IllegalStateException("Предпросмотр отсутствует, заменён или истёк"));return;}
        PREVIEWS.remove(player.getUUID());try{if(CartAuditContext.rollbackActive||RollbackCoordinator.hasActiveRollback())throw new IllegalStateException("Уже выполняется откат");
            absent(source.getServer(),id);active=new Job(source,preview);CartAuditContext.rollbackActive=true;
        }catch(Exception e){error(source,e);}
    }
    public static void joined(EntityJoinLevelEvent event){
        if(event.isCanceled()||!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel)
                ||!(event.getEntity() instanceof CarriageContraptionEntity e)||e.trainId==null)return;
        LOADED.computeIfAbsent(e.trainId,k->new HashMap<>()).put(e.getUUID(),new java.lang.ref.WeakReference<>(e));
        if(active!=null&&!CartAuditContext.restoring()&&active.preview.plan.id().equals(e.trainId))active.cancel("Оригинал загрузился во время восстановления");
    }
    public static void left(EntityLeaveLevelEvent event){
        if(!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel)||!(event.getEntity() instanceof CarriageContraptionEntity e))return;
        var entries=LOADED.get(e.trainId);if(entries!=null){entries.remove(e.getUUID());if(entries.isEmpty())LOADED.remove(e.trainId);}
    }
    static void absent(MinecraftServer server,UUID id){
        if(Create.RAILWAYS.trains.containsKey(id)||((CreateRailwayAccess)Create.RAILWAYS).avilixlogger$moving(id)>=0
                ||((CreateRailwayAccess)Create.RAILWAYS).avilixlogger$waiting(id)>=0)throw new IllegalStateException("Поезд уже существует в RailwayManager");
        var entries=LOADED.get(id);if(entries!=null)for(var ref:entries.values()){var e=ref.get();if(e!=null&&!e.isRemoved())throw new IllegalStateException("Найдено загруженное представление оригинала");}
    }
    /** Same bounded native staging kernel is exercised by server fixtures. */
    public static final class Restore {
        private final MinecraftServer server;private final UUID id;private final CreateTrainRegistry.Endpoint endpoint;
        public Restore(MinecraftServer server,CreateTrainLifecyclePlan plan,CreateTrainRegistry.Prepared prepared){
            this.server=server;id=plan.id();absent(server,id);var after=plan.registry().copy();after.putBoolean("Registered",false);after.putInt("Moving",-1);after.putInt("Waiting",-1);
            var station=after.getCompound("Station");if(station.hasUUID("Nearest")&&id.equals(station.getUUID("Nearest")))station.remove("Nearest");
            endpoint=new CreateTrainRegistry.Endpoint(server,plan.registry(),after,prepared);CartRestoreLocks.construction(id);
        }
        public boolean stage(){return endpoint.stage();}public void ready(){endpoint.ready();}
        public void install(){absent(server,id);endpoint.freeze();endpoint.install();}
        public void verify(){endpoint.verify();}public void compensate(){endpoint.compensate();}public void publish(){endpoint.publish();}
    }
    public static void onTick(ServerTickEvent.Post event){
        var job=active;if(job==null||!event.hasTime())return;if(!authorized(job.source.getPlayer()))job.cancel("Сессия завершена");
        boolean old=CartAuditContext.restoring(true);long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1);
        try{int count=0;do{if(job.step(event.getServer())){active=null;release();return;}}while(++count<8&&System.nanoTime()<deadline);}
        catch(Exception e){if(job.phase==9)CreateCartAudit.failed("train recovery compensation; locks retained",e);else job.cancel(e.getMessage());}
        finally{CartAuditContext.restoring(old);}
    }
    private static final class Job {
        final CommandSourceStack source;final Preview preview;final UUID actor;final long request=generation;Restore restore;
        int phase;boolean committed;String failure;
        Job(CommandSourceStack source,Preview preview){this.source=source;this.preview=preview;actor=source.getPlayer().getUUID();}
        void cancel(String reason){if(!committed){failure=reason;phase=9;}}
        boolean step(MinecraftServer server)throws Exception{
            if(phase==0){restore=new Restore(server,preview.plan,preview.prepared);phase=1;return false;}
            if(phase==1){if(!restore.stage())return false;phase=2;return false;}
            if(phase==2){restore.ready();phase=3;
                if(!CartRollbackCoordinator.query(source,()->{try{reserve(server,preview,actor);server.execute(()->{if(active==this&&phase==3&&request==generation)phase=4;});}
                    catch(Exception e){server.execute(()->{if(active==this)cancel(e.getMessage());});}}))cancel("Очередь записи занята");return false;}
            if(phase==3)return false;
            if(phase==4){restore.install();phase=5;return false;}
            if(phase==5){restore.verify();committed=true;phase=6;try{restore.publish();var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.CART_ROLLBACK;
                    row.actorUuid=actor;row.actorName=source.getTextName();row.dim=source.getLevel().dimension().location().toString();row.source=CreateContraptionKind.TRAIN.source(preview.plan.id(),"state_recovery");
                    row.extra="train_state_recovery; entry="+preview.entry+"; world_actions=0";var tag=new CompoundTag();tag.putInt("TrainStateFormat",1);tag.putString("Kind","train_state");tag.putBoolean("Complete",true);tag.put("Registry",preview.plan.registry());
                    row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,tag);LogIdGenerator.ensure(row);LoggerRuntime.storage(source.getLevel()).append(row);
                    source.sendSystemMessage(Component.literal("Поезд восстановлен из entry="+preview.entry+". Загруженные представления появятся штатно; выгруженные сохраняются в данных поезда."));
                }catch(Exception e){CreateCartAudit.failed("committed train receipt",e);}return true;}
            if(phase==6)return true;
            if(phase==9){if(restore!=null)restore.compensate();error(source,new IllegalStateException(Objects.toString(failure,"Отменено")));return true;}
            throw new IllegalStateException("Неизвестная фаза поезда");
        }
    }
    private static void reserve(MinecraftServer server,Preview preview,UUID actor)throws Exception{
        Path path=server.getWorldPath(LevelResource.ROOT).resolve("data/avilix-train-recovery/"+preview.plan.id()+".nbt");Files.createDirectories(path.getParent());
        var tag=new CompoundTag();tag.putUUID("Train",preview.plan.id());tag.putUUID("Actor",actor);tag.putLong("Entry",preview.entry);tag.put("Registry",preview.plan.registry());
        try(var channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var output=new java.io.ByteArrayOutputStream();NbtIo.writeCompressed(tag,output);
            var buffer=java.nio.ByteBuffer.wrap(output.toByteArray());while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }catch(FileAlreadyExistsException e){throw new IllegalStateException("Поезд уже восстановлен или зарезервирован. Повторение требует проверки сохранённой квитанции администратором");}
    }
    private static void error(CommandSourceStack source,Exception e){CartHistoryNetwork.error(source,"Восстановление поезда: "+Objects.toString(e.getMessage(),"ошибка"));}
    private static void release(){CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();CartAuditContext.rollbackActive=false;}
    public static void cancel(CommandSourceStack source){var p=source.getPlayer();if(p==null)return;PREVIEWS.remove(p.getUUID());REQUESTS.remove(p.getUUID());if(active!=null&&active.source.getPlayer()==p)active.cancel("Отменено администратором");}
    public static void logout(UUID id){PREVIEWS.remove(id);REQUESTS.remove(id);if(active!=null&&id.equals(active.actor))active.cancel("Администратор вышел");}
    public static void stop(){generation++;PREVIEWS.clear();REQUESTS.clear();LOADED.clear();var job=active;
        if(job!=null){boolean old=CartAuditContext.restoring(true);try{if(!job.committed&&job.restore!=null)job.restore.compensate();}finally{CartAuditContext.restoring(old);active=null;release();}}}
}


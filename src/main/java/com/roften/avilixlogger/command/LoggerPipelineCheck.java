package com.roften.avilixlogger.command;

import com.roften.avilixlogger.auth.AuthCoreBridge;
import com.roften.avilixlogger.core.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One operator receipt in flight; no world mutations, DB reconnect or AuthCore override. */
public final class LoggerPipelineCheck {
    private static final AtomicBoolean RUNNING=new AtomicBoolean();
    private LoggerPipelineCheck(){}
    public static int run(CommandSourceStack source){
        if(!PermissionUtil.has(source,"avilixlogger.command.admin",2)||!AuthCoreBridge.isAuthorized(source.getServer())){
            source.sendSystemMessage(Component.literal("Нужны права администратора и разрешение AuthCore."));return 0;
        }
        if(!RUNNING.compareAndSet(false,true)){source.sendSystemMessage(Component.literal("Проверка уже выполняется."));return 0;}
        if(!com.roften.avilixlogger.LoggerConfig.isEnabled()){RUNNING.set(false);source.sendSystemMessage(Component.literal("Logger отключён в основной конфигурации."));return 0;}
        try{
            var storage=LoggerRuntime.storage(source.getLevel());
            var entry=new LogEntry();entry.type=ActionType.LOGGER_DIAGNOSTIC;entry.source="logger:diagnostic";entry.dim="*";entry.ts=System.currentTimeMillis();
            var player=source.getPlayer();entry.actorUuid=player==null?null:player.getUUID();entry.actorName=player==null?"Консоль":player.getGameProfile().getName();
            entry.extra="Проверка доставки Logger · "+java.util.UUID.randomUUID();
            var tag=new net.minecraft.nbt.CompoundTag();tag.putString("Kind","LoggerPipelineCheck");tag.putBoolean("PositionKnown",false);tag.putString("Receipt",entry.extra);tag.putBoolean("AuthCoreConfirmed",true);entry.beAfter=tag.toString();
            if(!LoggerRuntime.enqueueDiagnostic(entry)){RUNNING.set(false);source.sendSystemMessage(Component.literal("Очередь не приняла служебную запись. Состояние видно в отчёте производительности."));return 0;}
            source.sendSystemMessage(Component.literal("Служебная запись принята: ID "+entry.id+". Проверяю доставку и чтение из базы…"));
            CompletableFuture.runAsync(()->{
                String result;
                try{
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);boolean visible=false;
                    var q=new LogQuery();q.exactId=entry.id;q.sourcePrefix="logger:diagnostic";q.requireDetails=true;q.limit=1;q.untilTs=Long.MAX_VALUE;
                    do{
                        if(!AuthCoreBridge.isAuthorized(source.getServer()))throw new IllegalStateException("Авторизация AuthCore отозвана.");
                        try{
                            var rows=storage.query(q);
                            if(!rows.isEmpty()){
                                var read=rows.getFirst();
                                if(read.type!=entry.type||!Objects.equals(read.beAfter,entry.beAfter)||!Objects.equals(read.actorUuid,entry.actorUuid)||!Objects.equals(read.extra,entry.extra))throw new IllegalStateException("Содержимое записи не совпало с отправленным.");
                                q.requireDetails=false;var feed=storage.query(q);q.requireDetails=true;
                                visible=feed.size()==1&&feed.getFirst().id==entry.id;if(visible)break;
                            }
                        }catch(IllegalStateException initializing){if(!LoggerRuntime.isInitializing())throw initializing;}
                        Thread.sleep(250);
                    }while(System.nanoTime()<deadline);
                    result=visible?"Проверка пройдена. AuthCore разрешил сервер; ID "+entry.id+" прошёл очередь, журнал и базу; подробности и строка GUI прочитаны по ID. Это проверка доставки, не замер TPS/FPS."
                        :"Доставка ID "+entry.id+" пока не подтверждена. Принятая запись остаётся в обработке. Проверьте отчёт производительности и серверный журнал.";
                }catch(Exception failure){result="Проверка не завершена: "+(failure instanceof InterruptedException?"задача прервана":failure.getMessage());}
                finally{RUNNING.set(false);}
                String answer=result;
                source.getServer().execute(()->{
                    if(PermissionUtil.has(source,"avilixlogger.command.admin",2)&&AuthCoreBridge.isAuthorized(source.getServer()))source.sendSystemMessage(Component.literal(answer));
                });
            }).exceptionally(failure->{RUNNING.set(false);return null;});
            return 1;
        }catch(RuntimeException failed){RUNNING.set(false);source.sendSystemMessage(Component.literal("Не удалось запустить проверку записи. Проверьте состояние Logger."));return 0;}
    }
}

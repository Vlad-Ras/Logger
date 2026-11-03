package com.roften.avilixlogger.core;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Observes the accepted native death, after every cancellable listener. Never changes death/drop flow. */
public final class PlayerDeathAudit {
    private static final LongAdder observed=new LongAdder(),disabled=new LongAdder(),unauthorized=new LongAdder();
    private static final LongAdder queued=new LongAdder(),rejected=new LongAdder(),errors=new LongAdder();
    private static final AtomicLong nextWarning=new AtomicLong();
    private PlayerDeathAudit(){}
    public static void reset(){observed.reset();disabled.reset();unauthorized.reset();queued.reset();rejected.reset();errors.reset();nextWarning.set(0);}
    public static long observedCount(){return observed.sum();}
    public static void accepted(ServerPlayer victim,DamageSource source) {
        observed.increment();
        try {
            if(!CaptureTypePolicy.enabled(29)){disabled.increment();return;}
            // The bulk queue's pause must not suppress these compact lifecycle receipts.
            if(!LoggerConfig.isEnabled()){unauthorized.increment();return;}
            var death=entry(victim,source);
            var storage=LoggerRuntime.storage(victim.serverLevel());
            storage.append(death);
            var kill=DeathLogDetails.killerRow(death,source);
            if(kill!=null)storage.append(kill);
        }catch(Throwable failure){failure(failure);}
    }
    /** Detached data kernel, shared by the native observer and isolated pipeline tests. */
    public static LogEntry entry(ServerPlayer victim,DamageSource source) {
        var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.PLAYER_DEATH;
        row.actorUuid=victim.getUUID();row.actorName=victim.getGameProfile().getName();
        row.entityUuid=row.actorUuid;row.entityName=row.actorName;row.entityType="minecraft:player";
        row.dim=victim.level().dimension().location().toString();
        var pos=victim.blockPosition();row.x=pos.getX();row.y=pos.getY();row.z=pos.getZ();
        String message=row.actorName+" умер";
        try{message=source.getLocalizedDeathMessage(victim).getString();}catch(Throwable failure){failure(failure);}
        row.extra="death_message:"+message;
        // A broken optional source/item component cannot erase the basic death receipt.
        try{row.extra=DeathLogDetails.capture(victim,source,message);}catch(Throwable failure){failure(failure);}
        try{WeaponCombatAudit.death(row,victim,source);}catch(Throwable failure){failure(failure);}
        return row;
    }
    static void admission(boolean accepted){
        if(accepted)queued.increment();
        else {rejected.increment();warn("[AvilixLogger] Player death reserve exhausted; inspect Logger GUI load report",null);}
    }
    private static void failure(Throwable failure){errors.increment();warn("[AvilixLogger] Player death audit failed; native death and drops continue",failure);}
    private static void warn(String message,Throwable failure){
        long now=System.currentTimeMillis(),next=nextWarning.get();
        if(now>=next && nextWarning.compareAndSet(next,now+60_000)) {
            try{if(failure==null)AvilixLoggerMod.LOGGER.warn(message);else AvilixLoggerMod.LOGGER.warn(message,failure);}catch(Throwable ignored){}
        }
    }
    public static String report(){
        return "Смерти игроков: запись "+(CaptureTypePolicy.enabled(29)?"включена":"ВЫКЛЮЧЕНА")
                +". С запуска: обнаружено "+observed.sum()+"; передано в очередь записей смерти/убийства "+queued.sum()
                +"; пропущено настройкой "+disabled.sum()+"; Logger выключен или нет авторизации "+unauthorized.sum()
                +"; не поместилось в резерв "+rejected.sum()+"; ошибок обработки "+errors.sum()
                +". Пауза массовой записи не отключает смерти; резерв до 256 записей / 1 МБ сверх обычной очереди.";
    }
}

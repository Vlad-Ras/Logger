package com.roften.avilixlogger.smoke;
import com.roften.avilixlogger.core.*;
import java.util.*;
import java.util.concurrent.*;
import static com.roften.avilixlogger.smoke.ScgunsNativeSmoke.check;

/** Actual server thread + production event queue; isolated data sink, no AuthCore override. */
final class ServerAdmissionNativeSmoke {
    static void run(net.minecraft.server.MinecraftServer server)throws Exception{
        check(server.isSameThread(),"fixture escaped server thread");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var rows=new CopyOnWriteArrayList<LogEntry>();
        LogStorage sink=new LogStorage(){
            public void append(LogEntry row){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}rows.add(row);}
            public List<LogEntry> query(LogQuery q){return List.copyOf(rows);}public List<LogEntry> queryReverse(LogQuery q){return query(q);}public void shutdown(){}
        };
        var storage=new AsyncLogStorage(sink,1,100_000);
        try{
            var accepted=new LogEntry();accepted.type=ActionType.ITEM_PICKUP;accepted.ts=System.currentTimeMillis();accepted.dim="minecraft:overworld";accepted.extra="accepted-before-overload";
            storage.append(accepted);check(entered.await(2,TimeUnit.SECONDS),"worker did not enter held sink");
            var refused=new LogEntry();refused.type=accepted.type;refused.ts=accepted.ts;refused.dim=accepted.dim;refused.extra="refused-under-overload";
            long began=System.nanoTime();storage.append(refused);long elapsed=System.nanoTime()-began;
            check(ServerCaptureGuard.paused()&&ServerCaptureGuard.active().queue().equals("events"),"real server did not use protected admission");
            check(elapsed<TimeUnit.SECONDS.toNanos(1)&&release.getCount()==1,"server waited for consumer release");
            check(!CaptureTypePolicy.allows(ActionType.CONTAINER_PUT,"player:menu",null),"snapshot gate not suspended");
            var player=PlayerDeathNativeSmoke.player(server.overworld(),"PausedExplosion");
            var source=server.overworld().damageSources().explosion(null,null);
            long observed=PlayerDeathAudit.observedCount();player.hurt(source,1000F);
            check(PlayerDeathAudit.observedCount()==observed+1,"paused native explosion was not observed");
            var death=PlayerDeathAudit.entry(player,source);storage.append(death);
            check(ServerCaptureGuard.paused()&&release.getCount()==1,"death bypass resumed bulk or waited for consumer");
            release.countDown();check(storage.awaitVisible(System.nanoTime()+TimeUnit.SECONDS.toNanos(2)),"accepted event did not drain");
            check(rows.size()==2&&rows.getFirst().extra.equals(accepted.extra)&&rows.get(1).type==ActionType.PLAYER_DEATH&&rows.get(1).actorUuid.equals(player.getUUID()),"accepted event lost or rejected event invented");
            System.out.printf(Locale.ROOT,"SERVER_CAPTURE_GUARD_NATIVE_OK: admission %.3f ms; real server thread, held consumer, suspension, preserved accepted row and paused explosion death reserve; AuthCore publication remains denied%n",elapsed/1e6);
        }finally{release.countDown();storage.shutdown();}
    }
}

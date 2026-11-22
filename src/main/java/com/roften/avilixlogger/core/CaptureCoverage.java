package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Durable fence for player/block proofs, not a claim that every mod has an audit adapter. */
public final class CaptureCoverage {
    private static volatile Ledger active;
    private static volatile boolean authorized;
    private static boolean settlementFailureReported;
    private static final java.util.concurrent.atomic.AtomicLong GENERATION=new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.ThreadPoolExecutor IO=new java.util.concurrent.ThreadPoolExecutor(0,1,30,java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(8),task->{var thread=new Thread(task,"avilixlogger-capture-coverage");thread.setDaemon(true);return thread;});
    public record Settings(String signature,boolean complete) {}
    private record Document(UUID epoch,String signature,boolean complete) {}
    private CaptureCoverage() {}
    public static Path path(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT).resolve("data/avilixlogger-capture.coverage");}
    public static synchronized void start(MinecraftServer server,boolean allowed) {
        authorized=allowed;
        settlementFailureReported=false;
        try {active=new Ledger(path(server),settings(),allowed);GENERATION.incrementAndGet();}
        catch(IOException problem){throw new IllegalStateException("Не удалось открыть проверку непрерывности записи Logger",problem);}
    }
    public static synchronized void stop(){active=null;authorized=false;settlementFailureReported=false;GENERATION.incrementAndGet();}
    public static long generation(){return GENERATION.get();}
    static void checkHealthy(){var current=active;if(current!=null&&current.failed)throw new IllegalStateException("replay_player_capture_coverage_unavailable: метка записи не сохранена");}
    public static UUID token(){var current=active;return current==null?null:current.token();}
    /** Before an admin mutation whose ordinary event capture is suppressed. One fence for the whole job. */
    public static void unrecordedMutation() {
        var current=active;if(current==null)return;
        // A later metadata/configuration failure must not prevent compensation of an already
        // durably fenced job. A failed initial revocation still blocks its first world mutation.
        if(current.unrecorded&&current.unrecordedDurable)return;
        checkHealthy();
        synchronized(CaptureCoverage.class) {
            if(active!=current||current.unrecorded&&current.unrecordedDurable)return;
            checkHealthy();
            GENERATION.incrementAndGet();
            try {current.beginUnrecorded();}
            catch(IOException problem){throw new IllegalStateException("Не удалось отозвать доказательства перед восстановлением",problem);}
        }
    }
    public static void onTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        settleMutations(CartAuditContext.rollbackActive||RollbackCoordinator.hasActiveRollback()||CartAuditContext.restoring());
    }
    static synchronized void settleMutations(boolean busy) {
        var current=active;if(current==null||!current.unrecorded||busy||current.failed)return;
        try {current.settleUnrecorded(settings(),authorized);GENERATION.incrementAndGet();settlementFailureReported=false;}
        catch(IOException problem) {
            if(!settlementFailureReported){settlementFailureReported=true;com.roften.avilixlogger.AvilixLoggerMod.LOGGER.error("[AvilixLogger] Новые доказательства восстановления заблокированы: метка записи не сохранена",problem);}
        }
    }
    public static synchronized void invalidate(String reason) {
        GENERATION.incrementAndGet();var current=active;if(current==null)return;
        try {current.invalidate();}
        catch(IOException problem){throw new IllegalStateException("Не удалось сохранить разрыв записи Logger: "+reason,problem);}
    }
    public static synchronized void changed() {
        var current=active;if(current==null)return;
        GENERATION.incrementAndGet();
        try {current.configure(settings(),authorized);}
        catch(IOException problem){throw new IllegalStateException("Не удалось сохранить настройки покрытия Logger",problem);}
    }
    public static void authorization(boolean allowed) {
        if(authorized!=allowed)authorizationChanged(allowed);
    }
    private static synchronized void authorizationChanged(boolean allowed) {
        if(authorized==allowed)return;
        if(!allowed)invalidate("authorization");
        authorized=allowed;
        if(allowed)changed();
    }
    static Settings settings() {
        var parts=new TreeMap<String,String>();boolean complete=true;
        try {
            // Explicit references survive obfuscation; reflective field names do not.
            var values=LoggerConfig.VALUES;int index=0;
            for(var value:List.of(values.enabled,values.logBlocks,values.logEntities,values.logContainers,values.logAe2,
                    values.logCreateMinecarts,values.logCreateTrains,values.logCreateBearings,values.logCreateOtherContraptions,
                    values.logCreateActorWork,values.logCreateCargoTransfers,values.logCreateLifecycle,values.logCreateMovement,
                    values.logItemCraftSmelt,values.logItemTransactions,values.logChat,values.logPlayerLifecycle,values.logItemUse,
                    values.logItemConsume,values.logItemUsePhases,values.logProjectileShots,values.logProjectileHits,values.logGuiOpen,
                    values.logEntityAttacks,values.logGenericBlockUse)) {
                boolean enabled=value.get();parts.put("flag:"+index++,Boolean.toString(enabled));complete&=enabled;
            }
            index=0;
            for(var value:List.of(values.clickHouseUrl,values.clickHouseDatabase,values.clickHouseTable,values.clickHouseSchemaMode,values.clickHouseTablePrefix))
                parts.put("storage:"+index++,value.get());
            // Overflow policy changes how a gap is handled, not which events are captured.
            long mask=CaptureTypePolicy.mask()&~(1L<<29);
            parts.put("mask",Long.toString(mask));complete&=mask==0;
            parts.put("handlers",String.join("\n",new TreeSet<>(CaptureHandlers.disabled())));complete&=CaptureHandlers.disabled().isEmpty();
            for(var mod:net.neoforged.fml.ModList.get().getMods())parts.put("mod:"+mod.getModId(),mod.getVersion().toString());
            parts.put("proof-schema","player-block-coverage-2");
        } catch(Exception problem){parts.put("unavailable",problem.getClass().getName());complete=false;}
        var encoded=new StringBuilder();parts.forEach((key,value)->encoded.append(key.length()).append(':').append(key).append(value.length()).append(':').append(value));
        return new Settings(hash(encoded.toString()),complete&&!ServerCaptureGuard.paused());
    }
    public static void verify(Path file,List<PlayerBlockTransfer.Proof> proofs) {
        if(proofs.isEmpty())return;
        try {
            var current=read(file);
            for(var proof:proofs)if(!current.complete()||proof.coverage()==null||!proof.coverage().equals(current.epoch()))
                throw new IllegalStateException("replay_player_capture_coverage_gap: #"+proof.event()+"; запись выключалась, настройки/сборка менялись или доказательство устарело");
        } catch(IOException problem){throw new IllegalStateException("replay_player_capture_coverage_unavailable: нет достоверной метки непрерывности записи",problem);}
    }
    /** Disk writes occur only at lifecycle/settings/fault boundaries, never once per inventory action. */
    static final class Ledger {
        private final Path file;
        private volatile Document document;
        private volatile boolean failed;
        private volatile boolean unrecorded;
        private volatile boolean unrecordedDurable;
        Ledger(Path file,Settings settings,boolean allowed) throws IOException {
            this.file=file;
            // A pending replacement is evidence of an interrupted boundary, even if the old file is intact.
            if(Files.exists(temporary(file),LinkOption.NOFOLLOW_LINKS)) {
                if(Files.isSymbolicLink(file.getParent())||!Files.isRegularFile(temporary(file),LinkOption.NOFOLLOW_LINKS))throw new IOException("Недостоверная незавершённая метка покрытия");
                Files.move(temporary(file),file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                forceDirectory(file.getParent());
            }
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))document=read(file);
            configure(settings,allowed);
        }
        UUID token(){var value=document;return failed||unrecorded||value==null||!value.complete()?null:value.epoch();}
        synchronized void beginUnrecorded() throws IOException {
            if(unrecorded)return;
            unrecorded=true;invalidate();unrecordedDurable=true;
        }
        synchronized void settleUnrecorded(Settings settings,boolean allowed) throws IOException {
            if(!unrecorded)return;
            unrecorded=false;unrecordedDurable=false;configure(settings,allowed);
        }
        synchronized void invalidate() throws IOException {
            if(failed)throw new IOException("Предыдущая запись метки покрытия не завершилась");
            if(document!=null&&!document.complete())return;
            publish(new Document(UUID.randomUUID(),document==null?"":document.signature(),false));
        }
        synchronized void configure(Settings settings,boolean allowed) throws IOException {
            if(failed)throw new IOException("Предыдущая запись метки покрытия не завершилась");
            boolean complete=settings.complete()&&allowed&&!unrecorded;
            var old=document;
            if(old!=null&&old.complete()==complete&&old.signature().equals(settings.signature()))return;
            publish(new Document(UUID.randomUUID(),settings.signature(),complete));
        }
        private void publish(Document next) throws IOException {
            // Clear admission before waiting for I/O. A failed boundary cannot certify another proof.
            failed=true;
            try {java.util.concurrent.CompletableFuture.runAsync(()->{try{write(file,next);}catch(IOException problem){throw new java.util.concurrent.CompletionException(problem);}},IO).join();document=next;failed=false;}
            catch(java.util.concurrent.CompletionException problem){
                // Do not leave a readable old permit when a replacement failed (for example ENOSPC).
                try{Files.deleteIfExists(file);forceDirectory(file.getParent());}
                catch(IOException revokeFailure){problem.addSuppressed(revokeFailure);}
                throw new IOException("Не удалось записать метку покрытия",problem.getCause());
            }
        }
    }
    private static Path temporary(Path file){return file.resolveSibling(file.getFileName()+".pending");}
    private static void write(Path file,Document value) throws IOException {
        boolean newDirectory=!Files.exists(file.getParent());Files.createDirectories(file.getParent());
        if(newDirectory)forceDirectory(file.getParent().getParent());
        if(Files.isSymbolicLink(file.getParent())||Files.isSymbolicLink(file))throw new IOException("Ссылка вместо метки покрытия");
        String body="AVILIX-COVERAGE-1\n"+value.epoch()+"\n"+value.signature()+"\n"+value.complete()+"\n";
        var bytes=(body+hash(body)+"\n").getBytes(StandardCharsets.UTF_8);
        try(var channel=FileChannel.open(temporary(file),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        // Make the pending boundary durable before replacing the previous permit.
        forceDirectory(file.getParent());
        Files.move(temporary(file),file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        forceDirectory(file.getParent());
    }
    private static Document read(Path file) throws IOException {
        if(Files.exists(temporary(file),LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                ||Files.isSymbolicLink(file.getParent())||Files.size(file)>1024)throw new IOException("Недостоверная метка покрытия: "+file);
        var lines=Files.readAllLines(file,StandardCharsets.UTF_8);
        if(lines.size()!=5||!lines.get(0).equals("AVILIX-COVERAGE-1")||!Set.of("true","false").contains(lines.get(3)))throw new IOException("Повреждена метка покрытия");
        String body=String.join("\n",lines.subList(0,4))+"\n";
        if(!hash(body).equals(lines.get(4)))throw new IOException("Не совпала контрольная сумма покрытия");
        try{return new Document(UUID.fromString(lines.get(1)),lines.get(2),Boolean.parseBoolean(lines.get(3)));}
        catch(IllegalArgumentException problem){throw new IOException("Некорректная метка покрытия",problem);}
    }
    private static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void forceDirectory(Path directory) throws IOException {try(var channel=FileChannel.open(directory,StandardOpenOption.READ)){channel.force(true);}}
}

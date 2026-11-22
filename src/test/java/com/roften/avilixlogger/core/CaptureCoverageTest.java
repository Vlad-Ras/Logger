package com.roften.avilixlogger.core;

import java.nio.file.*;
import java.util.*;

/** Exercises real fsync/atomic replacement and restart, not an in-memory imitation of the fence. */
public final class CaptureCoverageTest {
    private static final CaptureCoverage.Settings FULL=new CaptureCoverage.Settings("full-capture-v1",true);
    static void run() throws Exception {
        var directory=Files.createTempDirectory("capture-coverage");var file=directory.resolve("capture.coverage");
        try {
            var ledger=new CaptureCoverage.Ledger(file,FULL,true);profile(ledger);var first=ledger.token();check(first!=null,"missing complete token");
            var proof=proof(first);CaptureCoverage.verify(file,List.of(proof));
            check(first.equals(new CaptureCoverage.Ledger(file,FULL,true).token()),"safe restart discarded a usable crash proof");
            ledger.invalidate();check(ledger.token()==null,"disabled recording still issued tokens");
            refused(()->CaptureCoverage.verify(file,List.of(proof)),"coverage_gap");
            ledger.configure(FULL,true);check(!first.equals(ledger.token()),"reenabling restored the revoked token");
            refused(()->CaptureCoverage.verify(file,List.of(proof)),"coverage_gap");
            var current=proof(ledger.token());CaptureCoverage.verify(file,List.of(current));
            ledger.configure(new CaptureCoverage.Settings("blocks-disabled",false),true);
            check(ledger.token()==null,"partial coverage issued proof");
            ledger.configure(FULL,true);var full=ledger.token();
            ledger.configure(FULL,false);check(ledger.token()==null,"denied authorization issued proof");
            ledger.configure(FULL,true);check(!full.equals(ledger.token()),"authorization recovery reused old proof");
            var beforeSignature=ledger.token();ledger.configure(new CaptureCoverage.Settings("different-storage-or-mods",true),true);
            check(!beforeSignature.equals(ledger.token()),"changed storage/mod signature reused proof");
            check(!CaptureCoverage.settings().complete(),"headless unavailable config claimed coverage");

            // Corruption, missing files, symbolic links, and a partial pending write all fail closed.
            var missing=directory.resolve("missing");refused(()->CaptureCoverage.verify(missing,List.of(current)),"unavailable");
            var saved=Files.readAllBytes(file);Files.writeString(file,"broken");
            refused(()->CaptureCoverage.verify(file,List.of(current)),"unavailable");Files.write(file,saved);
            var link=directory.resolve("link");Files.createSymbolicLink(link,file);refused(()->CaptureCoverage.verify(link,List.of(current)),"unavailable");Files.delete(link);
            Files.writeString(file.resolveSibling(file.getFileName()+".pending"),"incomplete");
            refused(()->CaptureCoverage.verify(file,List.of(current)),"unavailable");
            try{new CaptureCoverage.Ledger(file,FULL,true);throw new AssertionError("partial pending boundary was accepted");}catch(java.io.IOException expected){}
            Files.delete(file); // interrupted pending was atomically adopted and is deliberately invalid

            ledger=new CaptureCoverage.Ledger(file,FULL,true);var beforeCrash=ledger.token();
            var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),CaptureCoverageTest.class.getName(),file.toString())
                    .redirectErrorStream(true).start();
            if(!child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("coverage crash subprocess timed out");}
            var output=new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            check(child.exitValue()==73&&output.contains("COVERAGE_BOUNDARY_DURABLE"),"real boundary crash failed: "+output);
            var restarted=new CaptureCoverage.Ledger(file,FULL,true);
            check(!beforeCrash.equals(restarted.token()),"new JVM reenabled the pre-gap proof");
            refused(()->CaptureCoverage.verify(file,List.of(proof(beforeCrash))),"coverage_gap");
            CaptureCoverage.verify(file,List.of(proof(restarted.token())));
            var blocked=file.resolveSibling(file.getFileName()+".pending");Files.createDirectory(blocked);
            try{restarted.invalidate();throw new AssertionError("failed boundary was accepted");}catch(java.io.IOException expected){}
            check(restarted.token()==null&&!Files.exists(file),"I/O failure left an old readable permit");Files.delete(blocked);
            refused(()->CaptureCoverage.verify(file,List.of(proof(beforeCrash))),"unavailable");
            long generation=CaptureCoverage.generation();long mask=CaptureTypePolicy.mask();
            try {
                CaptureTypePolicy.install(CaptureTypePolicy.changed(mask,2,false));CaptureTypePolicy.install(mask);
                check(CaptureCoverage.generation()>generation,"type off/on did not invalidate scene generation");
                generation=CaptureCoverage.generation();var old=CaptureHandlers.disabled();
                try{CaptureHandlers.install(Set.of(CaptureHandlers.at(0).key()));CaptureHandlers.install(old);check(CaptureCoverage.generation()>generation,"handler off/on did not invalidate scene generation");}
                finally{CaptureHandlers.install(old);}
            } finally {CaptureTypePolicy.install(mask);}
            System.out.println("CAPTURE_COVERAGE_OK: durable revoke/re-enable, safe restart, partial/auth/signature gates, corrupt/missing/link/pending refusal, actual JVM halt and native policy generation hooks");
        } finally {try(var walk=Files.walk(directory)){for(var path:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    public static void main(String[] args) throws Exception {
        var file=Path.of(args[0]);var ledger=new CaptureCoverage.Ledger(file,FULL,true);ledger.invalidate();
        // Simulate death after durable pending publication but before replacing the old permit.
        Files.copy(file,file.resolveSibling(file.getFileName()+".pending"));
        try(var channel=java.nio.channels.FileChannel.open(file.resolveSibling(file.getFileName()+".pending"),StandardOpenOption.WRITE)){channel.force(true);}
        try(var channel=java.nio.channels.FileChannel.open(file.getParent(),StandardOpenOption.READ)){channel.force(true);}
        System.out.println("COVERAGE_BOUNDARY_DURABLE");System.out.flush();Runtime.getRuntime().halt(73);
    }
    private static void profile(CaptureCoverage.Ledger ledger) throws Exception {
        long total=0,max=0;
        for(int i=0;i<10;i++){long began=System.nanoTime();ledger.invalidate();ledger.configure(FULL,true);long elapsed=System.nanoTime()-began;total+=elapsed;max=Math.max(max,elapsed);}
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();long thread=Thread.currentThread().threadId();
        for(int i=0;i<10000;i++)ledger.token();
        long before=bean.getThreadAllocatedBytes(thread),began=System.nanoTime();UUID last=null;
        for(int i=0;i<500000;i++)last=ledger.token();
        long elapsed=System.nanoTime()-began,allocated=bean.getThreadAllocatedBytes(thread)-before;
        check(last!=null,"hot path token disappeared");
        System.out.printf(java.util.Locale.ROOT,"CAPTURE_COVERAGE_PROFILE durable_off_on_pairs=10 avg_pair_ms=%.3f max_pair_ms=%.3f token_reads=500000 avg_read_ns=%.1f allocated_bytes=%d scope=local_boundary_io_and_token_only%n",total/10e6,max/1e6,elapsed/500000.0,allocated);
    }
    private static PlayerBlockTransfer.Proof proof(UUID token){return new PlayerBlockTransfer.Proof(1,1,UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",net.minecraft.core.BlockPos.ZERO,1,null,null,1,"fixture",token);}
    private static void refused(Runnable operation,String reason){try{operation.run();throw new AssertionError("accepted "+reason);}catch(IllegalStateException expected){check(expected.getMessage().contains(reason),"wrong refusal "+expected);}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

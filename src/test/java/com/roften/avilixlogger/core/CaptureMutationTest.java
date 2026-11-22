package com.roften.avilixlogger.core;

import java.nio.file.*;
import java.util.*;

/** A metadata fixture only: AuthCoreBridge is neither replaced nor granted authorization. */
public final class CaptureMutationTest {
    private static final CaptureCoverage.Settings FULL=new CaptureCoverage.Settings("mutation-fixture",true);
    static void run() throws Exception {
        var directory=Files.createTempDirectory("capture-mutation");var file=directory.resolve("coverage");
        var field=CaptureCoverage.class.getDeclaredField("active");field.setAccessible(true);var previous=field.get(null);
        boolean restoring=CartAuditContext.restoring();
        try {
            var ledger=new CaptureCoverage.Ledger(file,FULL,true);field.set(null,ledger);var token=ledger.token();var proof=proof(token);
            var bytes=Files.readAllBytes(file);long generation=CaptureCoverage.generation();
            boolean old=CartAuditContext.restoringJournal(true);
            try {check(CartAuditContext.restoring(),"journal scope did not suppress events");}
            finally {CartAuditContext.restoringJournal(old);}
            old=CartAuditContext.restoringDetached(true);
            try {check(CartAuditContext.restoring(),"detached scope did not suppress events");}
            finally {CartAuditContext.restoringDetached(old);}
            check(Arrays.equals(bytes,Files.readAllBytes(file))&&generation==CaptureCoverage.generation(),"preview or WAL application revoked history");
            old=CartAuditContext.restoring(true);
            try {
                check(ledger.token()==null,"unrecorded restore admitted a new proof");
                boolean nested=CartAuditContext.restoring(true);CartAuditContext.restoring(nested);
            } finally {CartAuditContext.restoring(old);}
            check(!CartAuditContext.restoring()&&CaptureCoverage.generation()==generation+1,"restoration nesting/fence generation broken");
            refused(()->CaptureCoverage.verify(file,List.of(proof)),"coverage_gap");
            var revoked=Files.readAllBytes(file);generation=CaptureCoverage.generation();long started=System.nanoTime();
            for(int i=0;i<10000;i++){old=CartAuditContext.restoring(true);CartAuditContext.restoring(old);}
            long elapsed=System.nanoTime()-started;
            check(Arrays.equals(revoked,Files.readAllBytes(file))&&generation==CaptureCoverage.generation(),"each batch rewrote the mutation fence");
            CaptureCoverage.settleMutations(true);check(ledger.token()==null,"busy restore resumed proof issuance");
            ledger.configure(FULL,true);check(ledger.token()==null,"settings refresh revived a suspended proof window");
            CaptureCoverage.settleMutations(false);
            ledger.configure(FULL,true);check(ledger.token()!=null&&!token.equals(ledger.token()),"idle settlement did not end mutation suspension or revived old period");
            CaptureCoverage.verify(file,List.of(proof(ledger.token())));refused(()->CaptureCoverage.verify(file,List.of(proof)),"coverage_gap");
            var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),CaptureMutationTest.class.getName(),file.toString())
                    .redirectErrorStream(true).start();
            if(!child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("mutation crash timed out");}
            var output=new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            check(child.exitValue()==73&&output.contains("MUTATION_FENCE_DURABLE"),"mutation subprocess failed: "+output);
            var recovered=new CaptureCoverage.Ledger(file,FULL,true);check(!recovered.token().equals(ledger.token()),"restart reused a pre-restore proof");
            field.set(null,recovered);old=CartAuditContext.restoring(true);CartAuditContext.restoring(old);
            var blocked=file.resolveSibling(file.getFileName()+".pending");Files.createDirectory(blocked);
            try{recovered.configure(new CaptureCoverage.Settings("failed-config",false),false);throw new AssertionError("metadata fault not injected");}
            catch(java.io.IOException expected){}
            // Once the old permit was durably revoked, cleanup must keep working even if a
            // subsequent metadata update fails; no new proof window is admitted.
            old=CartAuditContext.restoring(true);CartAuditContext.restoring(old);
            check(recovered.token()==null,"metadata fault reopened proof issuance");Files.delete(blocked);
            System.out.printf(java.util.Locale.ROOT,"CAPTURE_MUTATION_PROFILE repeated_scopes=10000 elapsed_ms=%.3f additional_fence_writes=0 scope=local_restoration_guard_only%n",elapsed/1e6);
            System.out.println("CAPTURE_MUTATION_OK: journal/detached scopes preserve evidence; durable unrecorded fence, nested cleanup, one fence per job, settings cannot reopen it, busy settlement, fresh period and actual JVM halt");
        } finally {CartAuditContext.restoringJournal(restoring);field.set(null,previous);try(var walk=Files.walk(directory)){for(var path:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    public static void main(String[] args) throws Exception {
        var ledger=new CaptureCoverage.Ledger(Path.of(args[0]),FULL,true);var field=CaptureCoverage.class.getDeclaredField("active");field.setAccessible(true);field.set(null,ledger);
        CartAuditContext.restoring(true);System.out.println("MUTATION_FENCE_DURABLE");System.out.flush();Runtime.getRuntime().halt(73);
    }
    static PlayerBlockTransfer.Proof proof(UUID token){return new PlayerBlockTransfer.Proof(1,1,UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",net.minecraft.core.BlockPos.ZERO,1,null,null,1,"fixture",token);}
    private static void refused(Runnable operation,String reason){try{operation.run();throw new AssertionError("accepted "+reason);}catch(IllegalStateException expected){check(expected.getMessage().contains(reason),"wrong refusal "+expected);}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

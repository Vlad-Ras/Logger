package com.roften.avilixlogger.core;

import java.lang.management.ManagementFactory;
import java.util.UUID;
import static com.roften.avilixlogger.core.AsyncPipelineTest.check;

final class CauseContextHotPathTest {
    static void run() throws Exception {
        verifyServerContextIsolation();

        var actor=new CauseContext.Cause(UUID.randomUUID(),"Editor",CauseContext.Kind.USE_BLOCK,null,null,0);
        try(var outer=CauseContext.push(actor)){
            try(var hidden=CauseContext.unattributed()){
                for(int i=0;i<1000;i++)try(var nested=CauseContext.unattributed()){check(CauseContext.peek()==null,"nested simulation inherited player");}
                check(CauseContext.peek()==null,"nested close exposed outer player");
                try{try(var nested=CauseContext.unattributed()){throw new IllegalStateException("fixture");}}catch(IllegalStateException expected){}
                check(CauseContext.peek()==null,"exception exposed outer player");
            }
            check(CauseContext.peek()==actor,"simulation lost original player");
            if(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean&&bean.isThreadAllocatedMemorySupported()){
                bean.setThreadAllocatedMemoryEnabled(true);
                try(var hidden=CauseContext.unattributed()){
                    for(int i=0;i<100000;i++){try(var scope=CauseContext.unattributed()){}try(var scope=CauseContext.push((CauseContext.Cause)null)){}}
                    long thread=Thread.currentThread().threadId(),start=bean.getThreadAllocatedBytes(thread);
                    for(int i=0;i<500000;i++)try(var scope=CauseContext.unattributed()){}
                    long optimized=bean.getThreadAllocatedBytes(thread)-start;start=bean.getThreadAllocatedBytes(thread);
                    for(int i=0;i<500000;i++)try(var scope=CauseContext.push((CauseContext.Cause)null)){}
                    long previous=bean.getThreadAllocatedBytes(thread)-start;
                    check(optimized<previous/4,"nested anonymous callback allocation was not reduced");
                    System.out.println("CAUSE_CONTEXT_ALLOCATION_OK: nested callback bytes/op="+(optimized/500000.0)+", previous push bytes/op="+(previous/500000.0));
                }
            }
        }
        check(CauseContext.peek()==null,"finished action retained player");CauseContext.clearCurrentThread();
    }

    private static void verifyServerContextIsolation() throws Exception {
        var actor = new CauseContext.Cause(UUID.randomUUID(), "Owner", CauseContext.Kind.USE_BLOCK, null, null, 0);
        ServerCaptureGuard.start(Thread.currentThread(), gap -> {});
        try {
            try (var source = MutationSourceResolver.push("mod:create"); var player = CauseContext.push(actor)) {
                try (var tick = MutationSourceResolver.push(MutationSourceResolver.VANILLA_SIMULATION);
                     var anonymous = CauseContext.unattributed()) {
                    check(CauseContext.peek() == null, "machine acquired the outer player");
                    var worker = java.util.concurrent.CompletableFuture.runAsync(() -> {
                        check(MutationSourceResolver.resolveExternalSource() == null && CauseContext.peek() == null,
                                "worker inherited a server context");
                        try (var ownSource = MutationSourceResolver.push("mod:worker"); var ownPlayer = CauseContext.push(actor)) {
                            check("mod:worker".equals(MutationSourceResolver.resolveExternalSource())
                                    && CauseContext.peek() == actor, "worker context was not usable");
                        }
                        CauseContext.clearCurrentThread();
                        MutationSourceResolver.clearCurrentThread();
                    });
                    worker.get(2, java.util.concurrent.TimeUnit.SECONDS);
                    check(MutationSourceResolver.VANILLA_SIMULATION.equals(MutationSourceResolver.resolveExternalSource())
                            && CauseContext.peek() == null, "worker overwrote server context");
                }
                check("mod:create".equals(MutationSourceResolver.resolveExternalSource()) && CauseContext.peek() == actor,
                        "nested simulation failed to restore server context");
            }
            check(MutationSourceResolver.resolveExternalSource() == null && CauseContext.peek() == null,
                    "server context leaked after closing outer scope");
        } finally {
            ServerCaptureGuard.stop();
        }
        ServerCaptureGuard.start(Thread.currentThread(), gap -> {});
        try {
            check(MutationSourceResolver.resolveExternalSource() == null && CauseContext.peek() == null,
                    "restarted server retained old context");
        } finally {
            ServerCaptureGuard.stop();
        }
    }
}

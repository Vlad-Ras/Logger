package com.roften.avilixlogger.core;

import java.lang.management.ManagementFactory;
import java.util.UUID;
import static com.roften.avilixlogger.core.AsyncPipelineTest.check;

final class CauseContextHotPathTest {
    static void run(){
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
}

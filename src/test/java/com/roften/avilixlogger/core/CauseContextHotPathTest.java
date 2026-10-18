package com.roften.avilixlogger.core;

import java.util.*;

/** Standalone context correctness plus bounded retention checks. */
public final class CauseContextHotPathTest {
    private static int checks;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    public static void main(String[] args)throws Exception {
        CauseContext.clearCurrentThread();
        var field=CauseContext.class.getDeclaredField("STACK");field.setAccessible(true);
        ThreadLocal<?> local=(ThreadLocal<?>)field.get(null);
        Object retained=local.get();
        for(int i=0;i<10000;i++)try(var scope=CauseContext.unattributed()){if(CauseContext.peek()!=null)throw new AssertionError("unexpected actor");}
        check(local.get()==retained,"normal ticks recreated thread-local deque");
        var cause=new CauseContext.Cause(UUID.randomUUID(),"actor",CauseContext.Kind.OTHER,null,null,0);
        try(var outer=CauseContext.push(cause)){
            try(var simulation=CauseContext.unattributed()){
                check(CauseContext.peek()==null,"simulation inherited actor");
                try(var inner=CauseContext.push(cause)){check(CauseContext.peek()==cause,"direct nested actor lost");}
                check(CauseContext.peek()==null,"actor escaped nested action");
            }
            check(CauseContext.peek()==cause,"outer actor not restored");
            try(var simulation=CauseContext.unattributed()){throw new IllegalStateException("fixture");}
            catch(IllegalStateException expected){check(CauseContext.peek()==cause,"exception lost outer cause");}
        }
        var empty=CauseContext.unattributed();
        check(empty==CauseContext.unattributed(),"empty tick allocated a scope");
        var later=CauseContext.push(cause);empty.close();empty.close();
        check(CauseContext.peek()==cause,"stale empty handle closed a later actor");later.close();
        var first=CauseContext.push(cause);var second=CauseContext.unattributed();
        first.close();first.close();check(CauseContext.peek()==null,"out-of-order close removed simulation");
        second.close();check(((Deque<?>)local.get()).isEmpty(),"closed scopes retained in deque");
        var causeField=CauseContext.Scope.class.getDeclaredField("cause");causeField.setAccessible(true);
        check(causeField.get(first)==null,"closed handle retained cause");
        var owner=CauseContext.push(cause);Thread other=new Thread(owner::close);other.start();other.join();
        check(CauseContext.peek()==cause,"foreign thread closed owner scope");owner.close();
        List<CauseContext.Scope> deep=new ArrayList<>();for(int i=0;i<200;i++)deep.add(CauseContext.push(cause));
        deep.getFirst().close();for(int i=deep.size()-1;i>0;i--)deep.get(i).close();
        check(CauseContext.peek()==null,"deep out-of-order unwind leaked actor");
        check(local.get()!=retained,"deep deque retained oversized capacity");
        retained=local.get();CauseContext.clearCurrentThread();check(local.get()!=retained,"lifecycle cleanup failed");
        CauseContext.clearCurrentThread();System.out.println("CONTEXT_HOT_PATH_OK "+checks+" checks");
    }
}

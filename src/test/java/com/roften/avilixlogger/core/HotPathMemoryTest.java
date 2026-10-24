package com.roften.avilixlogger.core;

import com.roften.avilixlogger.compat.ae2.Ae2Audit;
import com.roften.avilixlogger.compat.create.CartAutomationAudit;
import java.lang.reflect.Field;
import java.util.*;
import static com.roften.avilixlogger.core.AsyncPipelineTest.check;

final class HotPathMemoryTest {
    static void run() throws Exception {
        poweredScopes();reusableBuffers();metrics();
        System.out.println("HOTPATH_MEMORY_REGRESSION_OK");
    }
    private static void poweredScopes() throws Exception {
        check(!Ae2Audit.insidePowered(),"stale powered scope before test");
        Ae2Audit.enterPowered();
        try {
            Ae2Audit.enterPowered();
            try { throw new IllegalStateException("storage failure"); }
            catch(IllegalStateException expected) {}
            finally { Ae2Audit.leavePowered(); }
            check(Ae2Audit.insidePowered(),"nested exception removed outer scope");
        } finally { Ae2Audit.leavePowered(); }
        check(!Ae2Audit.insidePowered(),"failed operation suppressed later AE2 transfers");
        var field=Ae2Audit.class.getDeclaredField("POWERED_SCOPE");field.setAccessible(true);
        var local=(ThreadLocal<?>)field.get(null);Object idle=local.get();
        Ae2Audit.enterPowered();Ae2Audit.leavePowered();
        check(idle!=null&&local.get()==idle,"AE2 allocated another scope for a real transfer");
        Ae2Audit.leavePowered();check(!Ae2Audit.insidePowered(),"duplicate close reopened powered scope");
    }
    private static Field field(String name) throws Exception {
        Field field=CartAutomationAudit.Scope.class.getDeclaredField(name);field.setAccessible(true);return field;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static void reusableBuffers() throws Exception {
        var constructor=CartAutomationAudit.Scope.class.getDeclaredConstructor();constructor.setAccessible(true);
        Object scope=constructor.newInstance();
        List changes=(List)field("changes").get(scope);
        Map addresses=(Map)field("addresses").get(scope);
        Set handlers=(Set)field("directHandlers").get(scope);
        for(int i=0;i<256;i++){Object handler=new Object();changes.add(null);addresses.put(handler,null);handlers.add(handler);}
        var creative=new IdentityHashMap<>();creative.put(new Object(),1);field("mountedCreative").set(scope,creative);
        var clear=CartAutomationAudit.Scope.class.getDeclaredMethod("clear");clear.setAccessible(true);clear.invoke(scope);
        check(((List)field("changes").get(scope)).isEmpty(),"burst rows retained");
        check(((Map)field("addresses").get(scope)).isEmpty(),"world endpoint handlers retained");
        check(((Set)field("directHandlers").get(scope)).isEmpty(),"direct handlers retained");
        check(field("mountedCreative").get(scope)==null,"creative handlers retained after cleanup");
        check(field("changes").get(scope)!=changes&&field("addresses").get(scope)!=addresses&&field("directHandlers").get(scope)!=handlers,"burst backing arrays retained");
        Object small=field("changes").get(scope);clear.invoke(scope);
        check(field("changes").get(scope)==small,"idle cleanup allocated another buffer");
    }
    private static void metrics() {
        HotPathMetrics.start();
        HotPathMetrics.queued(ActionType.ME_PUT,"ae2:terminal");
        HotPathMetrics.queued(ActionType.BLOCK_BREAK,"create:bearing:fixture");
        String report=HotPathMetrics.stop();
        check(report.contains("AE2: 1")&&report.contains("Create подшипники: 1"),"diagnostic source counts incorrect");
        HotPathMetrics.queued(ActionType.ME_PUT,"ae2:terminal");
        check(HotPathMetrics.report().equals(report),"stopped counters changed");
        HotPathMetrics.clear();
    }
}

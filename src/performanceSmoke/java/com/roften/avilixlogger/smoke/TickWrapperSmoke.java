package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import com.roften.avilixlogger.auth.AuthCoreBridge;
import com.roften.avilixlogger.compat.create.CartAutomationAudit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import ru.avilix.authcore.api.AvilixAuthApi;
import java.util.*;

@EventBusSubscriber(modid="avilixlogger")
public final class TickWrapperSmoke {
    private static int checks;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    @SubscribeEvent public static void started(ServerStartedEvent event){
        if(!Boolean.getBoolean("avilixlogger.tickWrapperSmoke"))return;
        var counter=new java.util.concurrent.atomic.AtomicInteger();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e)->{
            if(counter.incrementAndGet()==20)run(event);
        });
    }
    private static void run(ServerStartedEvent event){
        try{
            var world=event.getServer().overworld();var pos=world.getSharedSpawnPos().above(8);world.setChunkForced(pos.getX()>>4,pos.getZ()>>4,true);
            world.setBlock(pos,com.simibubi.create.AllBlocks.ANDESITE_FUNNEL.getDefaultState(),3);
            BlockEntity be=world.getBlockEntity(pos);check(be!=null,"missing Create block entity");
            Class<?> type=Class.forName("net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity");
            var ctor=type.getDeclaredConstructors()[0];ctor.setAccessible(true);
            var calls=new int[1];var failure=new boolean[1];
            BlockEntityTicker<BlockEntity> callback=(level,p,state,b)->{calls[0]++;check(CauseContext.peek()==null,"ticker inherited outer player");if(failure[0])throw new IllegalStateException("tick fixture");};
            TickingBlockEntity ticker=(TickingBlockEntity)ctor.newInstance(world.getChunkAt(pos),be,callback);
            var cause=new CauseContext.Cause(UUID.randomUUID(),"outer",CauseContext.Kind.OTHER,pos,null,0);
            var finishCalls=new int[1];var startFailure=new boolean[1];var finishFailure=new boolean[1];
            CartAutomationHooks.install(new CartAutomationHooks.Observer(){
                public CartAutomationHooks.Capture tick(BlockEntity source){
                    if(startFailure[0])throw new IllegalStateException("capture start fixture");
                    check(CauseContext.peek()==null,"capture start inherited actor");
                    return success->{finishCalls[0]++;check(CauseContext.peek()==null,"capture publication inherited actor");check(success!=failure[0],"capture success flag wrong");if(finishFailure[0])throw new IllegalStateException("capture finish fixture");};
                }
                public CartAutomationHooks.Capture item(Object h,int slot){return null;}
                public CartAutomationHooks.Capture fluid(Object h){return null;}
                public void exposed(net.minecraft.world.level.Level l,BlockPos p,Object s,Object h){}
                public boolean locked(Object h){return false;}
            });
            AvilixAuthApi.allowed=true;AuthCoreBridge.attach(event.getServer());
            try(var outer=CauseContext.push(cause);var source=MutationSourceResolver.push("test:outer")){
                ticker.tick();check(calls[0]==1&&finishCalls[0]==1,"native ticker/finish not reached");
                check(CauseContext.peek()==cause&&"test:outer".equals(MutationSourceResolver.resolveExternalSource()),"normal tick lost outer context");
                failure[0]=true;boolean threw=false;try{ticker.tick();}catch(RuntimeException expected){threw=true;}check(threw&&finishCalls[0]==2,"throwing ticker not finalized");failure[0]=false;
                check(CauseContext.peek()==cause&&"test:outer".equals(MutationSourceResolver.resolveExternalSource()),"throwing tick leaked context");
                startFailure[0]=true;threw=false;try{ticker.tick();}catch(RuntimeException expected){threw=true;}check(threw,"capture start exception swallowed");startFailure[0]=false;
                check(CauseContext.peek()==cause&&"test:outer".equals(MutationSourceResolver.resolveExternalSource()),"capture start leaked source");
                finishFailure[0]=true;threw=false;try{ticker.tick();}catch(RuntimeException expected){threw=true;}check(threw,"capture finish exception swallowed");finishFailure[0]=false;
                check(CauseContext.peek()==cause&&"test:outer".equals(MutationSourceResolver.resolveExternalSource()),"capture finish leaked source");
            }
            CartAutomationHooks.install(new CartAutomationAudit());
            // Real Create observer on an idle native ticker: exactly one authorization check per tick.
            calls[0]=0;AuthCoreBridge.attach(event.getServer());AvilixAuthApi.calls=0;
            ticker.tick();ticker.tick();check(AvilixAuthApi.calls==1,"idle ticks repeated authorization: "+AvilixAuthApi.calls);
            AvilixAuthApi.allowed=false;
            check(!AuthCoreBridge.isAuthorized(),"direct publication check cached authorization");
            check(!AuthCoreBridge.isAuthorizedForTick(),"direct denial did not invalidate tick lease");
            AvilixAuthApi.allowed=true;AuthCoreBridge.attach(event.getServer());
            check(AuthCoreBridge.isAuthorizedForTick(),"reattach retained denial");
            var checkedAt=AuthCoreBridge.class.getDeclaredField("checkedAt");checkedAt.setAccessible(true);
            checkedAt.setLong(null,System.nanoTime()-51_000_000L);AvilixAuthApi.allowed=false;
            check(!AuthCoreBridge.isAuthorizedForTick(),"long tick extended positive authorization lease");
            AvilixAuthApi.allowed=true;AuthCoreBridge.attach(event.getServer());AuthCoreBridge.isAuthorizedForTick();
            var checkedTick=AuthCoreBridge.class.getDeclaredField("checkedTick");checkedTick.setAccessible(true);checkedTick.setInt(null,event.getServer().getTickCount()-1);
            AvilixAuthApi.allowed=false;check(!AuthCoreBridge.isAuthorizedForTick(),"authorization survived tick boundary");
            AvilixAuthApi.allowed=true;AuthCoreBridge.attach(event.getServer());AuthCoreBridge.isAuthorizedForTick();
            AvilixAuthApi.allowed=false;var workerResult=new boolean[1];Thread worker=new Thread(()->workerResult[0]=AuthCoreBridge.isAuthorizedForTick());worker.start();worker.join();
            check(!workerResult[0],"worker reused server-thread lease");check(!AuthCoreBridge.isAuthorizedForTick(),"worker denial did not invalidate lease");
            AvilixAuthApi.allowed=true;AuthCoreBridge.attach(event.getServer());AuthCoreBridge.isAuthorizedForTick();
            AvilixAuthApi.fail=true;check(!AuthCoreBridge.isAuthorized(),"API exception granted authorization");check(!AuthCoreBridge.isAuthorizedForTick(),"API exception left a positive lease");AvilixAuthApi.fail=false;
            AuthCoreBridge.clear(event.getServer());check(!AuthCoreBridge.isAuthorizedForTick(),"stopped server remained authorized");
            AuthCoreBridge.attach(event.getServer());
            var audit=new CartAutomationAudit();var scope=audit.authorizedTick(be);check(scope!=null,"real Create capture missing");
            check(audit.authorizedTick(be)==null,"nested capture reset reusable scope");
            var addresses=scope.getClass().getDeclaredField("addresses");addresses.setAccessible(true);
            ((Map<Object,Object>)addresses.get(scope)).put(new Object(),new Object());
            scope.finish(false);check(((Map<?,?>)addresses.get(scope)).isEmpty(),"failed capture retained endpoint");
            for(String field:List.of("source","entity","id")){var f=scope.getClass().getDeclaredField(field);f.setAccessible(true);check(f.get(scope)==null,"capture retained "+field);}
            var again=audit.authorizedTick(be);check(again==scope,"idle capture did not reuse scope");again.finish(true);
            AvilixAuthApi.allowed=false;check(audit.tick(be)==null,"authorization revocation ignored");
            check(CauseContext.peek()==null&&MutationSourceResolver.resolveExternalSource()==null,"fixture left context");
            System.out.println("TICK_WRAPPER_NATIVE_OK "+checks+" checks");
        }catch(Throwable failure){failure.printStackTrace();System.out.println("TICK_WRAPPER_NATIVE_FAILED");}
        finally{AvilixAuthApi.allowed=false;CartAutomationAudit.clear();CartAutomationHooks.install(new CartAutomationAudit());event.getServer().halt(false);}
    }
}

package com.roften.avilixlogger.core;

import com.roften.avilixlogger.net.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import io.netty.buffer.Unpooled;
import java.util.*;
import static com.roften.avilixlogger.core.AsyncPipelineTest.check;

/** Wire compatibility and validation for the full GUI workflow, without a running client. */
final class CartGuiWorkflowTest {
    static void run(){
        String id="51000000-0000-0000-0000-000000000001";
        for(var action:C2SCartActionPayload.Action.values()){
            var value=new C2SCartActionPayload(action,action.needsId()?id:"",action.needsId()?42:0);
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{C2SCartActionPayload.STREAM_CODEC.encode(buffer,value);check(C2SCartActionPayload.STREAM_CODEC.decode(buffer).equals(value),"action codec changed "+action);}finally{buffer.release();}
        }
        var filters=new C2SCartHistoryPayload(17,id,1234,"MaxKuz",true);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{C2SCartHistoryPayload.STREAM_CODEC.encode(buffer,filters);check(C2SCartHistoryPayload.STREAM_CODEC.decode(buffer).equals(filters),"GUI actor/cursor/checkpoint filter lost on wire");}finally{buffer.release();}
        var q=CartHistoryQuery.build(filters);check(q.cartId.equals(UUID.fromString(id))&&q.actorName.equals("MaxKuz")&&q.beforeId==1234&&q.types.contains(ActionType.CART_PACK)&&q.limit==13&&q.sinceTs==0&&q.untilTs==Long.MAX_VALUE,"GUI query omitted chat-find/history semantics");
        var next=q.copy();next.beforeId=100;check(next.actorName.equals(q.actorName)&&next.types.equals(q.types)&&next.cartId.equals(q.cartId),"next page lost GUI filters");
        check(C2SCartActionPayload.confirmation("log cart confirm-recovery "+id+" 42").action()==C2SCartActionPayload.Action.CONFIRM_RECOVERY,"item confirmation became world rollback");
        check(C2SCartActionPayload.confirmation("log cart confirm "+id+" 42").action()==C2SCartActionPayload.Action.CONFIRM_ROLLBACK,"rollback confirmation became item grant");
        for(String bad:List.of("op MaxKuz","log cart confirm "+id+" 42 extra","log cart confirm-recovery "+id+" 0","log cart recover "+id+" 42","log cart confirm broken 42","log cart confirm "+id+" -1"))reject(()->C2SCartActionPayload.confirmation(bad),"arbitrary/invalid confirmation accepted");
        reject(()->new C2SCartActionPayload(C2SCartActionPayload.Action.ROLLBACK,id,0),"rollback zero checkpoint accepted");
        reject(()->new C2SCartActionPayload(C2SCartActionPayload.Action.STATUS,id,1),"status accepted hidden mutation parameters");
        reject(()->CartHistoryQuery.build(new C2SCartHistoryPayload(1,"broken",0,"",false)),"invalid cart broadened history");
        for(int kind:new int[]{S2CCartHistoryPayload.ERROR,S2CCartHistoryPayload.NOTICE,S2CCartHistoryPayload.PROGRESS,S2CCartHistoryPayload.DONE}){
            var p=new S2CCartHistoryPayload(kind,0,"",0,false,"Полное сообщение результата","",List.of());var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{S2CCartHistoryPayload.STREAM_CODEC.encode(b,p);check(S2CCartHistoryPayload.STREAM_CODEC.decode(b).equals(p),"GUI result/progress lost on wire");}finally{b.release();}
        }
        String largeJson="{\"id\":42,\"beAfter\":\""+"сохранённые-данные-".repeat(4000)+"\"}";
        var large=new S2CLogDetailsPayload(42,C2SRequestDetailsPayload.Mode.JSON,List.of(net.minecraft.network.chat.Component.literal(largeJson)));
        var jsonBuffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{S2CLogDetailsPayload.STREAM_CODEC.encode(jsonBuffer,large);check(S2CLogDetailsPayload.STREAM_CODEC.decode(jsonBuffer).lines().getFirst().getString().equals(largeJson),"large JSON export truncated/disconnected at the old 32767 character boundary");}finally{jsonBuffer.release();}
        System.out.println("CART_GUI_WORKFLOW_OK: action/filter/result codecs, actor+ID+cursor+checkpoints before LIMIT, typed confirmation, mutation validation");
    }
    private static void reject(Runnable task,String message){try{task.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError(message);}
}

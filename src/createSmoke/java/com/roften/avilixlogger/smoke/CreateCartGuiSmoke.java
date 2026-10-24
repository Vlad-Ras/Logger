package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.net.*;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.*;

/** Exercises cancellation ownership and real CLOSE dispatch without bypassing AuthCore. */
final class CreateCartGuiSmoke {
    static void run(MinecraftServer server)throws Exception{
        var a=net.neoforged.neoforge.common.util.FakePlayerFactory.get(server.overworld(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"cart_gui_a"));
        var b=net.neoforged.neoforge.common.util.FakePlayerFactory.get(server.overworld(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"cart_gui_b"));
        Map<UUID,Object> recovery=map(CartItemRecovery.class,"REQUESTS"),rollback=map(CartRollbackCoordinator.class,"PREVIEW_REQUESTS"),issuing=map(CartItemRecovery.class,"ISSUING"),pages=map(CartHistoryNetwork.class,"LAST_REQUEST");
        @SuppressWarnings("unchecked") Set<UUID> viewers=(Set<UUID>)field(CartHistoryNetwork.class,"VIEWERS").get(null);
        var preparing=field(CartRollbackCoordinator.class,"preparing");var actor=field(CartRollbackCoordinator.class,"preparingActor");var entity=field(CartRollbackCoordinator.class,"preparingEntity");
        Object previousPreparing=preparing.get(null),previousActor=actor.get(null),previousEntity=entity.get(null);
        try{
            recovery.put(a.getUUID(),1L);recovery.put(b.getUUID(),2L);rollback.put(a.getUUID(),3L);rollback.put(b.getUUID(),4L);pages.put(a.getUUID(),5L);viewers.add(a.getUUID());
            UUID cart=UUID.randomUUID();issuing.put(a.getUUID(),cart);
            var context=(IPayloadContext)java.lang.reflect.Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),new Class<?>[]{IPayloadContext.class},(proxy,method,args)->{
                if(method.getName().equals("player"))return a;
                if(method.getName().equals("enqueueWork")){((Runnable)args[0]).run();return java.util.concurrent.CompletableFuture.completedFuture(null);}
                throw new UnsupportedOperationException(method.getName());
            });
            CartHistoryNetwork.handleAction(new C2SCartActionPayload(C2SCartActionPayload.Action.CLOSE,"",0),context);
            check(!viewers.contains(a.getUUID())&&!pages.containsKey(a.getUUID()),"close retained viewer/page session");
            check(!recovery.containsKey(a.getUUID())&&!rollback.containsKey(a.getUUID()),"late preview token survived GUI close");
            check(recovery.containsKey(b.getUUID())&&rollback.containsKey(b.getUUID()),"GUI close cancelled another administrator");
            check(CartItemRecovery.issuing(a.getUUID())&&CartItemRecovery.status(a.getUUID()).contains(cart.toString()),"close falsely cancelled a confirmed item reservation");
            preparing.set(null,true);actor.set(null,a.getUUID());entity.set(null,null);
            CartRollbackCoordinator.cancel(b.createCommandSourceStack());check((boolean)preparing.get(null),"another administrator cancelled preparation");
            CartRollbackCoordinator.cancel(a.createCommandSourceStack());check(!(boolean)preparing.get(null)&&actor.get(null)==null,"owner cancellation retained preparing job");
            System.out.println("CART_GUI_CANCEL_NATIVE_OK: typed close, pending-page/preview invalidation, administrator ownership, committed-reservation protection");
        }finally{
            for(var map:List.of(recovery,rollback,issuing,pages)){map.remove(a.getUUID());map.remove(b.getUUID());}viewers.remove(a.getUUID());
            preparing.set(null,previousPreparing);actor.set(null,previousActor);entity.set(null,previousEntity);
        }
    }
    private static java.lang.reflect.Field field(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    @SuppressWarnings("unchecked") private static Map<UUID,Object> map(Class<?> type,String name)throws Exception{return (Map<UUID,Object>)field(type,name).get(null);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Optional bridge: common capability/ticker mixins never link Create classes. */
public final class CartAutomationHooks {
    private CartAutomationHooks() {}
    public interface Capture { void finish(boolean success); }
    public interface Observer {
        Capture tick(BlockEntity source);
        /** Caller has checked simulation capture authorization for this synchronous tick. */
        default Capture authorizedTick(BlockEntity source) { return tick(source); }
        Capture item(Object handler,int slot);
        Capture fluid(Object handler);
        void exposed(Level level,BlockPos pos,Object side,Object handler);
        boolean locked(Object handler);
    }
    private static Observer observer;
    public static void install(Observer value){observer=value;}
    public static Capture tick(BlockEntity source){return observer==null?null:observer.tick(source);}
    public static Capture authorizedTick(BlockEntity source){return observer==null?null:observer.authorizedTick(source);}
    public static Capture item(Object handler,int slot,boolean simulate){return observer==null||simulate?null:observer.item(handler,slot);}
    public static Capture fluid(Object handler,boolean simulate){return observer==null||simulate?null:observer.fluid(handler);}
    public static void exposed(Level level,BlockPos pos,Object side,Object handler){if(observer!=null&&handler!=null)observer.exposed(level,pos,side,handler);}
    public static boolean locked(Object handler){return CartAuditContext.rollbackActive&&observer!=null&&observer.locked(handler);}
}

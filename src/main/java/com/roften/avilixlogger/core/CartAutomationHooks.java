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
        Capture baseHopper(net.minecraft.world.level.block.entity.Hopper hopper,Object base,net.minecraft.core.Direction side);
        boolean baseHopperLocked(net.minecraft.world.level.block.entity.Hopper hopper,Object base);
        Capture baseItem(net.minecraft.world.Container inventory,int slot);
        boolean baseLocked(Object inventory);
        void exposed(Level level,BlockPos pos,Object side,Object handler);
        boolean locked(Object handler);
    }
    private static Observer observer;
    public static void install(Observer value){observer=value;}
    public static Capture tick(BlockEntity source){return observer==null?null:observer.tick(source);}
    public static Capture authorizedTick(BlockEntity source){return observer==null?null:observer.authorizedTick(source);}
    public static Capture item(Object handler,int slot,boolean simulate){return observer==null||simulate?null:observer.item(handler,slot);}
    public static Capture fluid(Object handler,boolean simulate){return observer==null||simulate?null:observer.fluid(handler);}
    public static boolean installed(){return observer!=null;}
    public static Capture baseHopper(net.minecraft.world.level.block.entity.Hopper hopper,Object base){return baseHopper(hopper,base,net.minecraft.core.Direction.UP);}
    public static Capture baseHopper(net.minecraft.world.level.block.entity.Hopper hopper,Object base,net.minecraft.core.Direction side){return observer==null?null:observer.baseHopper(hopper,base,side);}
    public static boolean baseHopperLocked(net.minecraft.world.level.block.entity.Hopper hopper,Object base){return observer!=null&&observer.baseHopperLocked(hopper,base);}
    public static Capture baseItem(net.minecraft.world.Container inventory,int slot){return observer==null?null:observer.baseItem(inventory,slot);}
    public static boolean baseLocked(Object inventory){return observer!=null&&observer.baseLocked(inventory);}
    /** Reused on ordinary calls; a nested native ejection gets its own frame. */
    public static final class HopperCall { public Capture capture; public boolean active; }
    public static void exposed(Level level,BlockPos pos,Object side,Object handler){CreateDeployerHooks.before(level,pos);if(observer!=null&&handler!=null)observer.exposed(level,pos,side,handler);}
    public static boolean locked(Object handler){return CartAuditContext.rollbackActive&&observer!=null&&observer.locked(handler);}
}

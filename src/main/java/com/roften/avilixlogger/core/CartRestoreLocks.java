package com.roften.avilixlogger.core;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.inventory.AbstractContainerMenu;
import java.util.*;

/** Server-thread-only reservations. Empty on the ordinary hot path. */
public final class CartRestoreLocks {
    private static final Map<ResourceKey<Level>,LongOpenHashSet> BLOCKS=new HashMap<>();
    private static final net.minecraft.server.level.TicketType<Long> TICKET=net.minecraft.server.level.TicketType.create("avilixlogger_cart_restore",Long::compare);
    private static final Map<net.minecraft.server.level.ServerLevel,LongOpenHashSet> CHUNKS=new HashMap<>();
    public static void keepLoaded(net.minecraft.server.level.ServerLevel level,BlockPos pos){
        var chunk=new net.minecraft.world.level.ChunkPos(pos);long key=chunk.toLong();
        if(CHUNKS.computeIfAbsent(level,ignored->new LongOpenHashSet()).add(key))level.getChunkSource().addRegionTicket(TICKET,chunk,0,key);
    }
    private static final Set<Object> HANDLERS=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<AbstractContainerMenu> MENUS=Collections.newSetFromMap(new IdentityHashMap<>());
    private CartRestoreLocks(){}
    public static void block(Level level,BlockPos pos){BLOCKS.computeIfAbsent(level.dimension(),ignored->new LongOpenHashSet()).add(pos.asLong());}
    public static boolean blockLocked(Level level,BlockPos pos){if(!CartAuditContext.rollbackActive || CartAuditContext.restoring() || level==null || pos==null)return false;var positions=BLOCKS.get(level.dimension());return positions!=null&&positions.contains(pos.asLong());}
    public static void handler(Object handler){if(handler!=null)HANDLERS.add(handler);}
    public static boolean handlerLocked(Object handler){return CartAuditContext.rollbackActive&&!CartAuditContext.restoring()&&HANDLERS.contains(handler);}
    public static void menu(AbstractContainerMenu menu){MENUS.add(menu);}
    public static boolean menuLocked(AbstractContainerMenu menu){return CartAuditContext.rollbackActive&&MENUS.contains(menu);}
    public static void clear(){
        CHUNKS.forEach((level,positions)->{for(long key:positions)level.getChunkSource().removeRegionTicket(TICKET,new net.minecraft.world.level.ChunkPos(key),0,key);});CHUNKS.clear();
        BLOCKS.clear();HANDLERS.clear();MENUS.clear();}
}

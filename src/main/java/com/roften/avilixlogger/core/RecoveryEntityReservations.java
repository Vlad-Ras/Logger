package com.roften.avilixlogger.core;

import com.roften.avilixlogger.mixin.*;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.*;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import java.util.*;

/** A targeted UUID barrier. Ordinary ticks pay one inactive branch; no global entity freeze. */
public final class RecoveryEntityReservations {
    private static MinecraftServer server;
    private static Set<UUID> ids=Set.of();
    private static String failure;
    private static Set<String> scoreNames=Set.of();
    private static int chunkLimit,chunkCount;
    private static final Map<UUID,Entity> live=new HashMap<>();
    private static final Map<Object,LongOpenHashSet> chunks=new IdentityHashMap<>();
    private RecoveryEntityReservations() {}
    public static void begin(MinecraftServer owner,Set<UUID> wanted) {
        begin(owner,wanted,4096);
    }
    public static void begin(MinecraftServer owner,Set<UUID> wanted,int maximumChunks) {
        if(server!=null)throw new IllegalStateException("Другая проверка UUID уже выполняется");
        server=owner;failure=null;ids=Set.copyOf(wanted);CartAuditContext.LOCKED_ENTITIES.addAll(ids);
        scoreNames=ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.toUnmodifiableSet());
        chunkLimit=maximumChunks;chunkCount=0;
        for(var level:owner.getAllLevels()) {
            var manager=((RecoveryEntityLevelAccessor)level).avilixlogger$entityManager();
            @SuppressWarnings("unchecked") var access=(RecoveryEntityManagerAccessor<Entity>)manager;
            var sections=access.avilixlogger$entitySections();
            for(long chunk:sections.getAllChunksWithExistingSections())
                sections.getExistingSectionsInChunk(chunk).forEach(section->section.getEntities().forEach(entity->{if(ids.contains(entity.getUUID()))track(level,entity);}));
        }
    }
    public static boolean active(){return server!=null;}
    public static void verify(){if(failure!=null)throw new IllegalStateException(failure);}
    public static boolean locked(Entity entity){return server!=null&&!CartAuditContext.restoring()&&entity.isAddedToLevel()&&ids.contains(entity.getUUID());}
    public static boolean reserved(UUID id){return server!=null&&!CartAuditContext.restoring()&&ids.contains(id);}
    public static boolean scoreLocked(String name){return server!=null&&!CartAuditContext.restoring()&&scoreNames.contains(name);}
    public static boolean heldChunk(Object manager,long chunk){var values=chunks.get(manager);return server!=null&&values!=null&&values.contains(chunk);}
    public static void hold(ServerLevel level,net.minecraft.world.level.ChunkPos pos) {
        var manager=((RecoveryEntityLevelAccessor)level).avilixlogger$entityManager();
        var held=chunks.computeIfAbsent(manager,ignored->new LongOpenHashSet());
        if(held.contains(pos.toLong()))return;
        if(chunkCount>=chunkLimit){failure="entity_chunk_limit: "+chunkLimit+"; мир не изменён";return;}
        held.add(pos.toLong());chunkCount++;
        CartRestoreLocks.keepLoaded(level,pos.getWorldPosition());
        // A FULL block chunk is still HIDDEN to the entity manager (ticket level 33).
        // Queue its entity storage explicitly without changing visibility or running game ticks.
        ((RecoveryEntityManagerAccessor<?>)manager).avilixlogger$queueEntityChunk(pos.toLong());
    }
    public static Entity live(UUID id){var value=live.get(id);return value!=null&&!value.isRemoved()?value:null;}
    public static void joined(EntityJoinLevelEvent event) {
        if(server==null||!(event.getLevel() instanceof ServerLevel level)||level.getServer()!=server||!ids.contains(event.getEntity().getUUID()))return;
        if(!server.isSameThread())throw new IllegalStateException("Зарезервированная сущность добавляется вне серверного потока");
        if(!event.loadedFromDisk()&&!CartAuditContext.restoring()){event.setCanceled(true);return;}
        track(level,event.getEntity());
    }
    public static void track(ServerLevel level,Entity entity) {
        var previous=live.putIfAbsent(entity.getUUID(),entity);
        if(previous!=null&&previous!=entity&&!previous.isRemoved()){failure="UUID уже существует в другом объекте/измерении: "+entity.getUUID();return;}
        live.put(entity.getUUID(),entity);hold(level,new net.minecraft.world.level.ChunkPos(entity.blockPosition()));
    }
    public static void release() {CartAuditContext.LOCKED_ENTITIES.removeAll(ids);live.clear();chunks.clear();ids=Set.of();scoreNames=Set.of();server=null;failure=null;chunkCount=0;}
}

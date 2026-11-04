package com.roften.avilixlogger.compat.supplementaries;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Lazy native packet scope: no snapshots until the provider has accepted its editor. */
public final class SupplementariesTextAudit {
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    private static final Set<String> PROVIDERS=Set.of("net.mehvahdjukaar.supplementaries.common.block.tiles.DoormatBlockTile","net.mehvahdjukaar.supplementaries.common.block.tiles.NoticeBoardBlockTile","net.mehvahdjukaar.supplementaries.common.block.tiles.SignPostBlockTile");
    private SupplementariesTextAudit(){}
    public static boolean supported(BlockEntity entity){return entity!=null&&PROVIDERS.contains(entity.getClass().getName());}
    public static boolean enabled(){return CaptureTypePolicy.enabled(1)&&CaptureTypePolicy.enabled(2)&&LoggerConfig.isEnabled()&&LoggerConfig.VALUES.logBlocks.get()&&!CartAuditContext.restoring();}
    public static Scope begin(ServerPlayer player,BlockPos pos){
        if(!CaptureTypePolicy.enabled(1)||!CaptureTypePolicy.enabled(2)||player==null||!player.server.isSameThread()||!enabled()||CauseContext.actorUuid(player)==null)return null;
        try{var scope=new Scope(player,pos.immutable(),CURRENT.get());CURRENT.set(scope);return scope;}
        catch(RuntimeException|LinkageError failure){AdaptiveLogDiagnostics.adapterFailure("supplementaries-text-scope");return null;}
    }
    /** Called inside native acceptClientMessages, after its wax/claim checks, once per packet. */
    public static void beforeNativeText(){var scope=CURRENT.get();if(scope!=null)scope.before();}
    public static boolean active(){return CURRENT.get()!=null;}
    public static final class Scope implements AutoCloseable {
        private final ServerPlayer player;private final BlockPos pos;private final Scope previous;
        private BlockEntity entity;private BlockState state;private CompoundTag before;private boolean attempted,closed;
        private Scope(ServerPlayer player,BlockPos pos,Scope previous){this.player=player;this.pos=pos;this.previous=previous;}
        private void before(){
            if(attempted)return;attempted=true;
            try{if(!enabled())return;var level=player.serverLevel();if(!WorldSnapshotAccess.readable(level,pos))return;
                var be=level.getBlockEntity(pos);if(be==null||!PROVIDERS.contains(be.getClass().getName()))return;
                boolean structural=CreateStructuralHooks.before(level,pos),deployer=CreateDeployerHooks.before(level,pos);
                if(structural||deployer)return;
                entity=be;state=be.getBlockState();before=NbtSerde.snapshotBlockEntity(level,be);
            }catch(RuntimeException|LinkageError failure){before=null;AdaptiveLogDiagnostics.adapterFailure("supplementaries-text-before");}
        }
        public void finish(boolean completed){
            if(closed)return;
            try{if(before==null||!enabled())return;var level=player.serverLevel();
                if(!WorldSnapshotAccess.readable(level,pos)||level.getBlockEntity(pos)!=entity||entity.getBlockState()!=state){AdaptiveLogDiagnostics.adapterFailure("supplementaries-text-replaced");return;}
                var after=NbtSerde.snapshotBlockEntity(level,entity);
                var row=pendingRow(player.getUUID(),player.getGameProfile().getName(),level.dimension().location().toString(),pos,state,before,after,completed);
                close();if(row!=null)LoggerRuntime.storage(level).append(row);
            }catch(RuntimeException|LinkageError failure){AdaptiveLogDiagnostics.adapterFailure("supplementaries-text-after");}
            finally{close();}
        }
        public void close(){if(closed)return;closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    private static Map<String,CompoundTag> holders(CompoundTag tag){
        if(tag==null)return Map.of();var found=new LinkedHashMap<String,CompoundTag>();
        if(tag.contains("TextHolder",10))found.put("Основной текст",tag.getCompound("TextHolder"));
        for(String side:List.of("SignUp","SignDown"))if(tag.getCompound(side).contains("TextHolder",10))found.put(side.equals("SignUp")?"Верхний указатель":"Нижний указатель",tag.getCompound(side).getCompound("TextHolder"));
        return found;
    }
    private static Tag filtered(CompoundTag tag){return tag.contains("filtered_message")?tag.get("filtered_message"):tag.get("message");}
    public static boolean changed(CompoundTag before,CompoundTag after){
        if(before==null||after==null)return false;var b=holders(before);var a=holders(after);
        if(b.isEmpty()||!b.keySet().equals(a.keySet()))return false;
        for(String key:b.keySet())if(!Objects.equals(b.get(key).get("message"),a.get(key).get("message"))||!Objects.equals(filtered(b.get(key)),filtered(a.get(key))))return true;
        return false;
    }
    /** Detached kernel, not an authorization path. Snapshot tags transfer ownership. */
    public static LogEntry row(UUID editor,String name,String dim,BlockPos pos,BlockState state,CompoundTag before,CompoundTag after,boolean completed){
        if(editor==null||pos==null||state==null||!changed(before,after))return null;return captureRow(editor,name,dim,pos,state,before,after,completed,false);
    }
    /** Detached candidate for the ordered event worker, before SNBT encoding. */
    public static LogEntry pendingRow(UUID editor,String name,String dim,BlockPos pos,BlockState state,CompoundTag before,CompoundTag after,boolean completed){
        return captureRow(editor,name,dim,pos,state,before,after,completed,true);
    }
    private static LogEntry captureRow(UUID editor,String name,String dim,BlockPos pos,BlockState state,CompoundTag before,CompoundTag after,boolean completed,boolean deferred){
        if(editor==null||pos==null||state==null||before==null||after==null)return null;
        var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.BLOCK_ENTITY_NBT_CHANGE;row.dim=dim;row.x=pos.getX();row.y=pos.getY();row.z=pos.getZ();row.actorUuid=editor;row.actorName=name;row.source="mod:supplementaries:text_edit";row.extra="supplementaries_text_edit;completed="+completed;
        row.deferBlockBefore(state);row.deferBlockAfter(state);row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,before);row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,after);if(deferred)row.deferSnapshotDelta(SnapshotDeltaPolicy.SUPPLEMENTARIES_TEXT);return row;
    }
    public static List<Component> describe(ServerLevel level,LogEntry row){
        boolean display=SupplementariesAutomationAudit.SOURCE.equals(row.source);
        if(row.type!=ActionType.BLOCK_ENTITY_NBT_CHANGE||(!"mod:supplementaries:text_edit".equals(row.source)&&!display)||row.beBefore==null||row.beAfter==null||row.beBefore.length()>1_048_576||row.beAfter.length()>1_048_576)return List.of();
        var beforeTag=NbtSerde.fromSnbt(row.beBefore);var afterTag=NbtSerde.fromSnbt(row.beAfter);
        var before=holders(beforeTag);var after=holders(afterTag);var out=new ArrayList<Component>();
        if(before.keySet().equals(after.keySet()))for(String key:before.keySet()){out.add(Component.literal("Текст Supplementaries: "+key));lines(out,level,"До",before.get(key).get("message"));lines(out,level,"После",after.get(key).get("message"));
            if(!Objects.equals(filtered(before.get(key)),filtered(after.get(key)))){lines(out,level,"До (фильтр)",filtered(before.get(key)));lines(out,level,"После (фильтр)",filtered(after.get(key)));}}
        if(display)SupplementariesDisplayDetails.append(out,level,beforeTag,afterTag);
        return List.copyOf(out);
    }
    private static void lines(List<Component> out,ServerLevel level,String label,Tag raw){
        if(!(raw instanceof ListTag list)||list.size()>32)return;
        try{var decoded=ComponentSerialization.CODEC.listOf().parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE),raw).result();if(decoded.isEmpty())return;
            var messages=decoded.get();for(int i=0;i<messages.size();i++)out.add(Component.literal(label+" ["+(i+1)+"]: "+messages.get(i).getString()));
        }catch(RuntimeException ignored){}
    }
}

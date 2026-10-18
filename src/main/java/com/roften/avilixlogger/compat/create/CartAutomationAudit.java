package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.*;
import net.neoforged.neoforge.fluids.capability.*;
import net.neoforged.neoforge.items.*;
import java.lang.ref.WeakReference;
import java.util.*;

/** Sparse, bounded capture at real native machine operations; no idle inventory snapshots. */
public final class CartAutomationAudit implements CartAutomationHooks.Observer {
    private record Endpoint(WeakReference<ServerLevel> level,BlockPos pos,String side) {}
    private static final Map<Object,Endpoint> ADDRESSES=new WeakHashMap<>();
    private static Scope current;
    private static boolean publishing;
    private static volatile net.minecraft.server.MinecraftServer activeServer;
    private static final Scope REUSABLE=new Scope();
    public CartAutomationHooks.Capture tick(BlockEntity source){
        return CreateCartAudit.enabled()?authorizedTick(source):null;
    }
    @Override public CartAutomationHooks.Capture authorizedTick(BlockEntity source){
        if(current!=null||publishing||CartAuditContext.restoring()||!(source.getLevel() instanceof ServerLevel level)||!level.getServer().isSameThread())return null;
        if(!BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(source.getType()).getNamespace().equals("create"))return null;
        activeServer=level.getServer();REUSABLE.reset(source);return current=REUSABLE;
    }
    public void exposed(Level level,BlockPos pos,Object side,Object handler){
        if(!(level instanceof ServerLevel server)||!server.getServer().isSameThread()||current==null&&!CartAuditContext.rollbackActive||!(handler instanceof IItemHandler||handler instanceof IFluidHandler))return;
        activeServer=server.getServer();
        if(handler instanceof IFluidHandler&&server.getBlockEntity(pos) instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank&&tank.getControllerBE()!=null)pos=tank.getControllerBE().getBlockPos();
        String direction=side instanceof Direction d?d.getName():"none";
        if(current!=null){
            var old=current.addresses.get(handler);
            if(old!=null&&(old.level.get()!=server||!old.pos.equals(pos)))current.complete=false;
            if(current.addresses.size()<1024||old!=null)current.addresses.put(handler,new Endpoint(new WeakReference<>(server),pos.immutable(),direction));else current.complete=false;
        }
        var previous=ADDRESSES.get(handler);
        if(previous!=null&&(previous.level.get()!=server||!previous.pos.equals(pos))&&current!=null)current.complete=false;
        if(previous!=null&&previous.level.get()==server&&previous.pos.equals(pos)&&previous.side.equals(direction))return;
        ADDRESSES.put(handler,new Endpoint(new WeakReference<>(server),pos.immutable(),direction));
    }
    public boolean locked(Object handler){
        if(!serverThread())return false;
        if(handler instanceof net.neoforged.neoforge.items.wrapper.InvWrapper wrapper&&wrapper.getInv() instanceof BlockEntity be&&CartRestoreLocks.blockLocked(be.getLevel(),be.getBlockPos()))return true;
        if(handler instanceof net.neoforged.neoforge.items.wrapper.InvWrapper wrapper&&CartRestoreLocks.containerLocked(wrapper.getInv()))return true;
        var endpoint=ADDRESSES.get(handler);var level=endpoint==null?null:endpoint.level.get();return level!=null&&CartRestoreLocks.blockLocked(level,endpoint.pos);
    }
    public CartAutomationHooks.Capture item(Object object,int slot){
        if(!serverThread())return null;
        Scope scope=current;if(scope==null||scope.mountedDepth>0||scope.handlerDepth>0||!(object instanceof IItemHandler handler))return null;
        if(CartStorageAudit.owner(object)!=null)return null;
        scope.handlerDepth++;ItemStack before;
        try{before=handler.getStackInSlot(slot).copy();}catch(RuntimeException e){scope.handlerDepth--;scope.complete=false;return null;}
        return success->{try{
            if(!success)scope.complete=false;ItemStack after=handler.getStackInSlot(slot);
            if(!ItemStack.matches(before,after))scope.external(object,"item",slot,before,after);
        }catch(RuntimeException e){scope.complete=false;CreateCartAudit.failed("automation item snapshot",e);}finally{scope.handlerDepth--;}};
    }
    public CartAutomationHooks.Capture fluid(Object object){
        if(!serverThread())return null;
        Scope scope=current;if(scope==null||scope.mountedDepth>0||scope.handlerDepth>0||!(object instanceof IFluidHandler handler))return null;
        if(CartStorageAudit.owner(object)!=null)return null;
        if(handler.getTanks()>64){scope.complete=false;return null;}
        scope.handlerDepth++;var before=CartStorageAudit.fluids(handler);
        return success->{try{
            if(!success||before.length!=handler.getTanks())scope.complete=false;
            for(int i=0;i<Math.min(before.length,handler.getTanks());i++)if(!FluidStack.matches(before[i],handler.getFluidInTank(i)))scope.external(object,"fluid",i,before[i],handler.getFluidInTank(i));
        }catch(RuntimeException e){scope.complete=false;CreateCartAudit.failed("automation fluid snapshot",e);}finally{scope.handlerDepth--;}};
    }
    private static boolean serverThread(){var server=activeServer;return server!=null&&server.isSameThread();}
    public static Scope enterMounted(){Scope scope=serverThread()?current:null;if(scope!=null)scope.mountedDepth++;return scope;}
    public static void leaveMounted(Scope scope){if(scope!=null)scope.mountedDepth--;}
    static boolean capture(CartStorageAudit.Ref ref,CompoundTag before,CompoundTag after,String detail){
        Scope scope=serverThread()?current:null;if(scope==null)return false;return scope.cargo(ref,before,after,detail);
    }
    public static void clear(){current=null;publishing=false;activeServer=null;REUSABLE.clear();ADDRESSES.clear();}
    private record Change(CompoundTag before,CompoundTag after,String detail) {}
    private record Cell(String dimension,long pos,String kind,int slot) {}
    private record Pair(CompoundTag before,CompoundTag after) {}
    public static final class Scope implements CartAutomationHooks.Capture {
        private BlockEntity source;private OrientedContraptionEntity entity;private UUID id;
        private final List<Change> changes=new ArrayList<>();
        private final LinkedHashMap<Cell,Pair> external=new LinkedHashMap<>();
        private final Map<Object,Endpoint> addresses=new IdentityHashMap<>();
        private boolean complete;private int mountedDepth,handlerDepth;
        private Scope(){}
        private void reset(BlockEntity source){this.source=source;complete=true;mountedDepth=handlerDepth=0;}
        private void clear(){
            source=null;entity=null;id=null;
            if(!changes.isEmpty())changes.clear();
            if(!external.isEmpty())external.clear();
            if(!addresses.isEmpty())addresses.clear();
            mountedDepth=handlerDepth=0;
        }
        private boolean cargo(CartStorageAudit.Ref ref,CompoundTag before,CompoundTag after,String detail){
            var e=ref.entity().get();if(e==null)return false;
            if(entity!=null&&entity!=e){complete=false;return false;}
            if(changes.size()>=512){complete=false;return false;}
            entity=e;changes.add(new Change(before,after,detail));return true;
        }
        private void external(Object handler,String kind,int slot,Object before,Object after){
            if(external.size()>=512){complete=false;return;}
            Endpoint endpoint=addresses.get(handler);if(endpoint==null)endpoint=ADDRESSES.get(handler);ServerLevel level=endpoint==null?null:endpoint.level.get();
            if(level==null||!level.hasChunkAt(endpoint.pos)){complete=false;return;}
            var metadata=CartAutomationStorage.describe(level,endpoint.pos,endpoint.side,kind,slot,handler);
            if(metadata==null){complete=false;return;}
            var b=metadata.copy();var a=metadata.copy();String field=kind.equals("item")?"Item":"Fluid";
            try{
                if(before instanceof ItemStack stack&&!stack.isEmpty())b.put(field,stack.save(level.registryAccess()));
                if(after instanceof ItemStack stack&&!stack.isEmpty())a.put(field,stack.save(level.registryAccess()));
                if(before instanceof FluidStack stack&&!stack.isEmpty())b.put(field,stack.save(level.registryAccess()));
                if(after instanceof FluidStack stack&&!stack.isEmpty())a.put(field,stack.save(level.registryAccess()));
            }catch(RuntimeException e){complete=false;return;}
            var key=new Cell(metadata.getString("Dimension"),metadata.getLong("Pos"),kind,metadata.getInt("Slot"));Pair previous=external.get(key);
            if(previous!=null&&(!previous.after.getCompound(field).equals(b.getCompound(field))||!CartAutomationStorage.sameAddress(previous.after,b)))complete=false;
            external.put(key,new Pair(previous==null?b:previous.before,a));
        }
        /** Returns detached rows; production publishes them, native fixtures exercise the same protocol. */
        private List<LogEntry> rows(boolean success){
            if(entity==null||changes.isEmpty())return List.of();
            id=UUID.randomUUID();ListTag beforeSlots=new ListTag(),afterSlots=new ListTag();
            for(Pair pair:external.values())if(!pair.before.equals(pair.after)){beforeSlots.add(pair.before);afterSlots.add(pair.after);}
            var before=envelope(success&&complete,beforeSlots);var after=envelope(success&&complete,afterSlots);
            List<LogEntry> rows=new ArrayList<>();
            for(int i=0;i<changes.size();i++){
                Change change=changes.get(i);for(var tag:new CompoundTag[]{change.before,change.after}){tag.putUUID("Transfer",id);tag.putInt("TransferIndex",i);tag.putString("TransferKind","automation");}
                rows.add(row(ActionType.CART_CONTENT_CHANGE,"cargo",change.before,change.after,change.detail));
            }
            rows.add(row(ActionType.CART_TRANSFER,"automation_transfer",before,after,"machine="+BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(source.getType())+"; changes="+changes.size()+"; external="+beforeSlots.size()));
            return List.copyOf(rows);
        }
        private CompoundTag envelope(boolean complete,ListTag slots){
            var n=new CompoundTag();n.putInt("Format",1);n.putString("Kind","automation");n.putUUID("Transfer",id);n.putUUID("Cart",CreateCartAudit.state(entity).id);
            n.putInt("Changes",changes.size());n.putBoolean("Complete",complete);n.put("Slots",slots);
            n.putString("Machine",BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(source.getType()).toString());n.putString("MachineDim",source.getLevel().dimension().location().toString());n.putLong("MachinePos",source.getBlockPos().asLong());return n;
        }
        private LogEntry row(ActionType type,String phase,CompoundTag before,CompoundTag after,String detail){
            var s=CreateCartAudit.state(entity);var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=type;row.dim=entity.level().dimension().location().toString();row.x=entity.blockPosition().getX();row.y=entity.blockPosition().getY();row.z=entity.blockPosition().getZ();
            row.actorName="SYSTEM";var actor=CauseContext.peek();if(actor!=null&&actor.actorUuid()!=null){row.actorUuid=actor.actorUuid();row.actorName=actor.actorName();}
            row.entityUuid=entity.getUUID();row.entityType="create:contraption";row.source=s.stamp(phase).source();row.extra="cart="+s.id+"; owner="+s.ownerName+"; automation; "+detail;
            row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,before);row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,after);LogIdGenerator.ensure(row);return row;
        }
        public void finish(boolean success){
            // Empty ticks need neither serialization nor a second authorization call.
            // Stop capture during publication, but prevent reentrant ticks from resetting REUSABLE.
            current=null;publishing=true;
            try{if(entity!=null&&!changes.isEmpty()&&CreateCartAudit.enabled())for(var row:rows(success))LoggerRuntime.storage((ServerLevel)entity.level()).append(row);}
            catch(RuntimeException e){CreateCartAudit.failed("automation transfer",e);}
            finally{publishing=false;clear();}
        }
    }
}

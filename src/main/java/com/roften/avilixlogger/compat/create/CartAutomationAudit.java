package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
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
    private static net.minecraft.world.Container baseHopperContainer;
    public static boolean baseHopperCovered(Object container){return container==baseHopperContainer;}
    public boolean baseLocked(Object inventory){return CartStorageAudit.locked(CartStorageAudit.ref(inventory));}
    public CartAutomationHooks.Capture baseItem(net.minecraft.world.Container inventory,int slot){
        var ref=CartStorageAudit.ref(inventory);if(ref==null||baseHopperCovered(inventory))return null;
        var entity=ref.entity().get();if(entity==null||!CreateCargoAudit.active(entity))return null;
        var before=inventory.getItem(slot).copy();return success->CartStorageAudit.changed(ref,slot,before,inventory.getItem(slot));
    }
    public boolean baseHopperLocked(net.minecraft.world.level.block.entity.Hopper hopper,Object base){
        var ref=CartStorageAudit.ref(base);if(ref==null)return false;
        if(CartStorageAudit.locked(ref))return true;
        return CartAuditContext.rollbackActive&&(CartRestoreLocks.containerLocked(hopper)
                ||hopper instanceof BlockEntity be&&CartRestoreLocks.blockLocked(be.getLevel(),be.getBlockPos()));
    }
    /** Snapshot the selected base and hopper, before either native endpoint changes. */
    public CartAutomationHooks.Capture baseHopper(net.minecraft.world.level.block.entity.Hopper hopper,Object base,Direction side){
        var ref=CartStorageAudit.ref(base);var entity=ref==null?null:ref.entity().get();
        if(ref==null||!ref.base()||entity==null||!CreateCargoAudit.active(entity)||baseHopperContainer!=null
                ||!(base instanceof net.minecraft.world.Container inventory))return null;
        if(!(hopper instanceof BlockEntity be)||!(be.getLevel() instanceof ServerLevel level))return null;
        int size=inventory.getContainerSize();if(size<0||size>512)return null;
        var before=new ItemStack[size];for(int i=0;i<size;i++)before[i]=inventory.getItem(i).copy();
        boolean owns=current==null;Scope scope=current;
        if(owns){activeServer=level.getServer();scope=new Scope();scope.reset(be);scope.entity=entity;scope.cargoDelivery=true;current=scope;}
        else if(scope.entity!=null&&scope.entity!=entity){scope.complete=false;return null;}
        final Scope transfer=scope;var external=container(hopper,level,be.getBlockPos(),side);
        transfer.handlerDepth++;baseHopperContainer=inventory;
        return success->{
            baseHopperContainer=null;transfer.handlerDepth--;
            try{
                if(!success||inventory.getContainerSize()!=size)transfer.complete=false;
                for(int i=0;i<Math.min(size,inventory.getContainerSize());i++)CartStorageAudit.changed(ref,i,before[i],inventory.getItem(i));
            }catch(RuntimeException error){transfer.complete=false;CreateCartAudit.failed("base hopper snapshot",error);}
            finally{try{if(external!=null)external.finish(success);}finally{if(owns)transfer.finish(success);}}
        };
    }
    public CartAutomationHooks.Capture tick(BlockEntity source){
        return CreateCartAudit.enabled()?authorizedTick(source):null;
    }
    @Override public CartAutomationHooks.Capture authorizedTick(BlockEntity source){
        if(!com.roften.avilixlogger.LoggerConfig.VALUES.logCreateCargoTransfers.get()
                ||current!=null||publishing||CartAuditContext.restoring()
                ||!(source.getLevel() instanceof ServerLevel level)||!level.getServer().isSameThread())return null;
        var blockType = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(source.getType());
        if(!"create".equals(blockType.getNamespace()))return null;
        // Avoid an automation scope on bearing controller ticks when bearings are muted.
        if(!com.roften.avilixlogger.LoggerConfig.VALUES.logCreateBearings.get()
                && (blockType.getPath().contains("bearing") || blockType.getPath().contains("clockwork")))return null;
        activeServer=level.getServer();REUSABLE.reset(source);REUSABLE.publish=true;return current=REUSABLE;
    }
    /** Detached native-operation capture; this scope never publishes to the journal. */
    public static Scope captureChanges(BlockEntity source){
        if(current!=null||!(source.getLevel() instanceof ServerLevel level)||!level.getServer().isSameThread())throw new IllegalStateException("Automation capture requires an unoccupied server-thread scope");
        activeServer=level.getServer();var scope=new Scope();scope.reset(source);current=scope;return scope;
    }
    static boolean capturingChanges(){return current!=null&&serverThread();}
    public static Scope mounted(com.simibubi.create.content.contraptions.behaviour.MovementContext context,BlockPos pos){
        var entity=context.contraption.entity;
        if(current!=null||entity==null||!CreateCargoAudit.active(entity))return null;
        activeServer=((ServerLevel)entity.level()).getServer();var scope=new Scope();
        scope.reset(null);scope.mounted=true;scope.entity=entity;
        scope.machine=BuiltInRegistries.BLOCK.getKey(context.state.getBlock()).toString();
        scope.machineDim=entity.level().dimension().location().toString();scope.machinePos=pos.immutable();
        return current=scope;
    }
    /** Native Container writes bypass capability wrappers; snapshot only this addressed recipient. */
    public static CartAutomationHooks.Capture container(net.minecraft.world.Container container,ServerLevel level,BlockPos pos,Direction side){
        var scope=serverThread()?current:null;if(scope==null)return null;
        scope.direct=true;int size=container.getContainerSize();
        if(size<0||size>512||scope.addresses.size()>=1024){scope.complete=false;return null;}
        var handler=new net.neoforged.neoforge.items.wrapper.InvWrapper(container);
        scope.directHandlers.add(handler);
        scope.addresses.put(handler,new Endpoint(new WeakReference<>(level),pos.immutable(),side.getName()));
        var before=new ItemStack[size];
        try{for(int i=0;i<size;i++)before[i]=container.getItem(i).copy();}catch(RuntimeException error){scope.complete=false;return null;}
        return success->{
            try{
            if(!success||container.getContainerSize()!=size)scope.complete=false;
            for(int i=0;i<Math.min(size,container.getContainerSize());i++){
                var after=container.getItem(i);if(!ItemStack.matches(before[i],after))scope.external(handler,"item",i,before[i],after);
            }
            }catch(RuntimeException error){scope.complete=false;CreateCartAudit.failed("direct container snapshot",error);}
        };
    }
    public void exposed(Level level,BlockPos pos,Object side,Object handler){
        if(!(level instanceof ServerLevel server)||!server.getServer().isSameThread()||current==null&&!CartAuditContext.rollbackActive||!(handler instanceof IItemHandler||handler instanceof IFluidHandler))return;
        activeServer=server.getServer();
        if(handler instanceof IFluidHandler&&server.getBlockEntity(pos) instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank&&tank.getControllerBE()!=null)pos=tank.getControllerBE().getBlockPos();
        String direction=side instanceof Direction d?d.getName():"none";
        if(current!=null){
            // Native creative handlers report their actual IO through dedicated wrappers.
            // An unknown adapter around a creative block has no verified IO contract.
            if(worldCreativeBlock(server,pos)&&!worldCreativeHandler(handler))current.complete=false;
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
        Scope scope=current;if(scope==null||!(object instanceof IItemHandler handler))return null;
        if(worldCreativeAddress(scope,object)&&!worldCreativeHandler(object))scope.complete=false;
        if(scope.mountedDepth>0||scope.handlerDepth>0)return null;
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
        Scope scope=current;if(scope==null||!(object instanceof IFluidHandler handler))return null;
        if(worldCreativeAddress(scope,object)&&!worldCreativeHandler(object))scope.complete=false;
        if(scope.mountedDepth>0||scope.handlerDepth>0)return null;
        if(CartStorageAudit.owner(object)!=null)return null;
        if(handler.getTanks()>64){scope.complete=false;return null;}
        scope.handlerDepth++;var before=CartStorageAudit.fluids(handler);
        return success->{try{
            if(!success||before.length!=handler.getTanks())scope.complete=false;
            for(int i=0;i<Math.min(before.length,handler.getTanks());i++)if(!FluidStack.matches(before[i],handler.getFluidInTank(i)))scope.external(object,"fluid",i,before[i],handler.getFluidInTank(i));
        }catch(RuntimeException e){scope.complete=false;CreateCartAudit.failed("automation fluid snapshot",e);}finally{scope.handlerDepth--;}};
    }
    /** Actual native result: an unchanged infinite template cannot express this transfer. */
    public static void worldCreativeItem(Object handler,ItemStack template,ItemStack payload,boolean inbound){
        Scope scope=serverThread()?current:null;
        if(scope==null||payload==null||payload.isEmpty())return;
        if(scope.worldCreative.size()>=512){unknownCreative(scope,"item",inbound?"in":"out",payload,"operation limit");return;}
        Endpoint endpoint=scope.addresses.get(handler);
        if(endpoint==null)endpoint=ADDRESSES.get(handler);
        var level=endpoint==null?null:endpoint.level.get();
        if(level==null||!level.hasChunkAt(endpoint.pos)||!worldCreativeBlock(level,endpoint.pos)){unknownCreative(scope,"item",inbound?"in":"out",payload,"missing endpoint");return;}
        CompoundTag tag;
        try{tag=creativeAddress(level,endpoint,handler,"item");}
        catch(RuntimeException error){unknownCreative(scope,"item",inbound?"in":"out",payload,"invalid address");CreateCartAudit.failed("world creative address",error);return;}
        if(tag==null){unknownCreative(scope,"item",inbound?"in":"out",payload,"unverified capability");return;}
        tag.putString("Direction",inbound?"in":"out");
        try{
            tag.put("Template",template!=null&&!template.isEmpty()?template.save(level.registryAccess()):new CompoundTag());
            tag.put("Payload",payload.save(level.registryAccess()));
            if(tag.sizeInBytes()>128*1024){unknownCreative(scope,"item",inbound?"in":"out",null,"payload too large");return;}
            scope.worldCreative.add(tag);
        }catch(RuntimeException error){unknownCreative(scope,"item",inbound?"in":"out",null,"serialization failure");CreateCartAudit.failed("world creative item snapshot",error);}
    }
    public static void worldCreativeFluid(Object handler,FluidStack template,FluidStack payload,boolean inbound){
        Scope scope=serverThread()?current:null;
        if(scope==null||payload==null||payload.isEmpty())return;
        if(scope.worldCreative.size()>=512){unknownCreative(scope,"fluid",inbound?"in":"out",payload,"operation limit");return;}
        Endpoint endpoint=scope.addresses.get(handler);
        if(endpoint==null)endpoint=ADDRESSES.get(handler);
        var level=endpoint==null?null:endpoint.level.get();
        if(level==null||!level.hasChunkAt(endpoint.pos)||!worldCreativeBlock(level,endpoint.pos)){unknownCreative(scope,"fluid",inbound?"in":"out",payload,"missing endpoint");return;}
        CompoundTag tag;
        try{tag=creativeAddress(level,endpoint,handler,"fluid");}
        catch(RuntimeException error){unknownCreative(scope,"fluid",inbound?"in":"out",payload,"invalid address");CreateCartAudit.failed("world creative address",error);return;}
        if(tag==null){unknownCreative(scope,"fluid",inbound?"in":"out",payload,"unverified capability");return;}
        tag.putString("Direction",inbound?"in":"out");
        try{
            tag.put("Template",template!=null&&!template.isEmpty()?template.save(level.registryAccess()):new CompoundTag());
            tag.put("Payload",payload.save(level.registryAccess()));
            if(tag.sizeInBytes()>128*1024){unknownCreative(scope,"fluid",inbound?"in":"out",null,"payload too large");return;}
            scope.worldCreative.add(tag);
        }catch(RuntimeException error){unknownCreative(scope,"fluid",inbound?"in":"out",null,"serialization failure");CreateCartAudit.failed("world creative fluid snapshot",error);}
    }
    public static boolean worldCreativeActive(){return serverThread()&&current!=null;}
    public static boolean worldCreativeActive(Object handler){
        return worldCreativeActive()&&(current.mountedCreative==null||!current.mountedCreative.containsKey(handler));
    }
    public static void worldCreativeUnverified(){if(serverThread()&&current!=null)unknownCreative(current,"unknown","unknown",null,"unverified native result");}
    private static void unknownCreative(Scope scope,String kind,String direction,Object payload,String reason){
        scope.complete=false;
        if(scope.unverifiedCreative.size()>=512)return;
        var tag=new CompoundTag();tag.putString("Kind",kind);tag.putString("Direction",direction);tag.putString("Reason",reason);
        try{
            ServerLevel level=scope.entity!=null?(ServerLevel)scope.entity.level():scope.source!=null&&scope.source.getLevel() instanceof ServerLevel server?server:null;
            if(level!=null){
                if(payload instanceof ItemStack item&&!item.isEmpty())tag.put("Payload",item.save(level.registryAccess()));
                else if(payload instanceof FluidStack fluid&&!fluid.isEmpty())tag.put("Payload",fluid.save(level.registryAccess()));
                if(tag.sizeInBytes()>128*1024)tag.remove("Payload");
            }
        }catch(RuntimeException ignored){tag.remove("Payload");}
        scope.unverifiedCreative.add(tag);
    }
    private static CompoundTag creativeAddress(ServerLevel level,Endpoint endpoint,Object handler,String kind){
        var be=level.getBlockEntity(endpoint.pos);
        if(be==null||be.isRemoved()||!worldCreativeBlock(level,endpoint.pos))return null;
        Direction side=endpoint.side.equals("none")?null:Direction.byName(endpoint.side);
        if(!endpoint.side.equals("none")&&side==null)return null;
        Object exposed=kind.equals("item")?level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,endpoint.pos,side)
                :level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,endpoint.pos,side);
        if(exposed!=handler)return null;
        var tag=new CompoundTag();tag.putString("Kind",kind);
        tag.putString("Dimension",level.dimension().location().toString());tag.putLong("Pos",endpoint.pos.asLong());
        tag.putString("Side",endpoint.side);tag.putString("Handler",handler.getClass().getName());
        tag.put("Block",NbtUtils.writeBlockState(level.getBlockState(endpoint.pos)));
        tag.putString("BlockEntity",BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString());
        tag.putInt("Size",kind.equals("item")?((IItemHandler)handler).getSlots():((IFluidHandler)handler).getTanks());
        tag.putInt("Limit",kind.equals("item")?((IItemHandler)handler).getSlotLimit(0):((IFluidHandler)handler).getTankCapacity(0));
        return tag;
    }
    private static boolean worldCreativeAddress(Scope scope,Object handler){
        if(worldCreativeHandler(handler))return true;
        Endpoint endpoint=scope.addresses.get(handler);
        if(endpoint==null)endpoint=ADDRESSES.get(handler);
        var level=endpoint==null?null:endpoint.level.get();
        return level!=null&&worldCreativeBlock(level,endpoint.pos);
    }
    private static boolean worldCreativeHandler(Object handler){
        String name=handler.getClass().getName();
        return name.equals("com.simibubi.create.content.logistics.crate.BottomlessItemHandler")
                ||name.equals("com.simibubi.create.content.fluids.tank.CreativeFluidTankBlockEntity$CreativeSmartFluidTank");
    }
    private static boolean worldCreativeBlock(ServerLevel level,BlockPos pos){
        if(!level.hasChunkAt(pos))return false;
        var id=BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return id.getNamespace().equals("create")
                &&(id.getPath().equals("creative_crate")||id.getPath().equals("creative_fluid_tank"));
    }
    private static boolean serverThread(){var server=activeServer;return server!=null&&server.isSameThread();}
    public static Scope enterMounted(){Scope scope=serverThread()?current:null;if(scope!=null)scope.mountedDepth++;return scope;}
    public static void leaveMounted(Scope scope){if(scope!=null)scope.mountedDepth--;}
    public static Scope enterMounted(Object creativeHandler){
        var scope=enterMounted();if(scope!=null&&creativeHandler!=null){
            if(scope.mountedCreative==null)scope.mountedCreative=new IdentityHashMap<>();
            scope.mountedCreative.merge(creativeHandler,1,Integer::sum);
        }return scope;
    }
    public static void leaveMounted(Scope scope,Object creativeHandler){
        if(scope!=null&&creativeHandler!=null&&scope.mountedCreative!=null){
            int count=scope.mountedCreative.getOrDefault(creativeHandler,0);
            if(count<=1)scope.mountedCreative.remove(creativeHandler);else scope.mountedCreative.put(creativeHandler,count-1);
        }leaveMounted(scope);
    }
    public static void worldItems(List<net.minecraft.world.entity.Entity> items) {
        var scope=serverThread()?current:null;if(scope==null||!scope.mounted)return;
        for(var item:items) {
            if(scope.worldBefore.size()>=512){scope.complete=false;break;}
            if(item instanceof net.minecraft.world.entity.item.ItemEntity||item instanceof com.simibubi.create.content.logistics.box.PackageEntity)
                scope.worldBefore.putIfAbsent(item,scope.world(item,null));
        }
    }
    public static boolean worldCovered(net.minecraft.world.entity.Entity item){return serverThread()&&current!=null&&current.worldBefore.containsKey(item);}
    public static void worldMergeChanged(net.minecraft.world.entity.Entity first,net.minecraft.world.entity.Entity second){
        Scope scope=serverThread()?current:null;
        if(scope!=null&&(scope.worldBefore.containsKey(first)||scope.worldBefore.containsKey(second)))scope.complete=false;
    }
    static boolean capture(CartStorageAudit.Ref ref,CompoundTag before,CompoundTag after,String detail){
        Scope scope=serverThread()?current:null;if(scope==null)return false;return scope.cargo(ref,before,after,detail);
    }
    public static void clear(){current=null;publishing=false;activeServer=null;baseHopperContainer=null;REUSABLE.clear();ADDRESSES.clear();}
    static boolean captureInfinite(CartStorageAudit.Ref ref,CompoundTag before,CompoundTag after){
        if(current==null)return false;
        if(!CreateCreativeTransferPlan.observed(before,after))current.complete=false;
        return current.cargo(ref,before,after,"creative storage supply/void");
    }
    private record Change(AbstractContraptionEntity entity,CompoundTag before,CompoundTag after,String detail,LogEntry prepared) {}
    private record Cell(String dimension,long pos,String kind,int slot) {}
    private record Pair(CompoundTag before,CompoundTag after) {}
    public static final class Scope implements CartAutomationHooks.Capture {
        private BlockEntity source;private AbstractContraptionEntity entity;private UUID id;
        private String machine,machineDim;private BlockPos machinePos;private boolean mounted,direct,cargoDelivery;
        private final List<Change> changes=new ArrayList<>();
        private final LinkedHashMap<Cell,Pair> external=new LinkedHashMap<>();
        private final Map<Object,Endpoint> addresses=new IdentityHashMap<>();
        private final Set<Object> directHandlers=Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<net.minecraft.world.entity.Entity,CompoundTag> worldBefore=new LinkedHashMap<>();
        private final ListTag worldCreative=new ListTag();
        private final ListTag unverifiedCreative=new ListTag();
        private boolean complete,publish;private int mountedDepth,handlerDepth;
        private Map<Object,Integer> mountedCreative;
        private Scope(){}
        private void reset(BlockEntity source){
            if(CreateDeployerAudit.capturing())CreateDeployerTransaction.unsupported("Вложенная автоматика активатора требует общего снимка всех внешних участников");
            this.source=source;entity=null;id=null;complete=true;publish=false;mountedDepth=handlerDepth=0;mounted=direct=false;
            cargoDelivery=false;machine=source==null?null:BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(source.getType()).toString();
            machineDim=source==null?null:source.getLevel().dimension().location().toString();machinePos=source==null?null:source.getBlockPos();}
        private boolean cargo(CartStorageAudit.Ref ref,CompoundTag before,CompoundTag after,String detail){
            var e=ref.entity().get();if(e==null)return false;
            if(entity!=null&&entity.level()!=e.level()){complete=false;return false;}
            if(changes.size()>=512){complete=false;return false;}
            if(entity==null)entity=e;
            boolean io=after.getString("Kind").equals("storage_io");
            var prepared=CreateCargoAudit.row(e,io?ActionType.CART_TRANSFER:ActionType.CART_CONTENT_CHANGE,io?"storage_io":"cargo",before,after,detail);
            changes.add(new Change(e,CreateCargoAudit.metadata(e,before),CreateCargoAudit.metadata(e,after),detail,prepared));return true;
        }
        private void external(Object handler,String kind,int slot,Object before,Object after){
            if(external.size()>=512){complete=false;return;}
            Endpoint endpoint=addresses.get(handler);if(endpoint==null)endpoint=ADDRESSES.get(handler);ServerLevel level=endpoint==null?null:endpoint.level.get();
            if(level==null||!level.hasChunkAt(endpoint.pos)){complete=false;return;}
            var metadata=directHandlers.contains(handler)&&handler instanceof net.neoforged.neoforge.items.wrapper.InvWrapper wrapper
                    ?CartAutomationStorage.describeContainer(level,endpoint.pos,endpoint.side,slot,wrapper.getInv())
                    :CartAutomationStorage.describe(level,endpoint.pos,endpoint.side,kind,slot,handler);
            if(metadata==null){
                complete=false;metadata=new CompoundTag();metadata.putBoolean("Unresolved",true);metadata.putString("Dimension",level.dimension().location().toString());
                metadata.putLong("Pos",endpoint.pos.asLong());metadata.putString("Side",endpoint.side);metadata.putString("Kind",kind);metadata.putInt("Slot",slot);metadata.putString("Handler",handler.getClass().getName());
                if(handler instanceof net.neoforged.neoforge.items.wrapper.InvWrapper wrapper&&wrapper.getInv() instanceof net.minecraft.world.entity.Entity receiver){
                    metadata.putUUID("ContainerEntity",receiver.getUUID());metadata.putString("EntityType",BuiltInRegistries.ENTITY_TYPE.getKey(receiver.getType()).toString());
                    var name=EntityLogIdentity.name(receiver);if(name!=null)metadata.putString("EntityName",name);
                }
            }
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
            if(entity==null)return worldCreative.isEmpty()&&unverifiedCreative.isEmpty()?List.of():worldCreativeRows();
            if(changes.isEmpty()&&external.isEmpty()&&worldBefore.isEmpty()&&worldCreative.isEmpty()&&unverifiedCreative.isEmpty())return List.of();
            if(changes.isEmpty())complete=false;
            ListTag beforeEntities=new ListTag(),afterEntities=new ListTag();
            for(var entry:worldBefore.entrySet()){
                var b=entry.getValue();var a=world(entry.getKey(),b);
                if(!b.getCompound("Item").equals(a.getCompound("Item"))){beforeEntities.add(b);afterEntities.add(a);}
            }
            if(mounted&&!direct&&external.isEmpty()&&beforeEntities.isEmpty()&&worldCreative.isEmpty()&&unverifiedCreative.isEmpty()
                    &&changes.stream().allMatch(c->c.entity==entity&&!c.after.getString("Kind").equals("storage_io"))){
                for(var change:changes){change.prepared.actorUuid=null;change.prepared.actorName="SYSTEM";}
                return changes.stream().map(Change::prepared).toList();
            }
            id=UUID.randomUUID();ListTag beforeSlots=new ListTag(),afterSlots=new ListTag();
            for(Pair pair:external.values())if(!pair.before.equals(pair.after)){beforeSlots.add(pair.before);afterSlots.add(pair.after);}
            if(changes.stream().allMatch(c->c.after.getString("Kind").equals("storage_io"))&&beforeSlots.isEmpty()&&beforeEntities.isEmpty()&&worldCreative.isEmpty())complete=false;
            var before=envelope(success&&complete,beforeSlots,false);var after=envelope(success&&complete,afterSlots,true);
            if(!worldCreative.isEmpty()){
                before.put("WorldCreativeIO",worldCreative.copy());after.put("WorldCreativeIO",worldCreative.copy());
            }
            if(!unverifiedCreative.isEmpty()){
                before.put("UnverifiedCreativeIO",unverifiedCreative.copy());after.put("UnverifiedCreativeIO",unverifiedCreative.copy());
            }
            if(!beforeEntities.isEmpty()){before.put("Entities",beforeEntities);after.put("Entities",afterEntities);}
            List<LogEntry> rows=new ArrayList<>();
            for(int i=0;i<changes.size();i++){
                Change change=changes.get(i);for(var tag:new CompoundTag[]{change.before,change.after}){tag.putUUID("Transfer",id);tag.putInt("TransferIndex",i);tag.putString("TransferKind","automation");}
                boolean io=change.after.getString("Kind").equals("storage_io");
                if(change.prepared!=null){change.prepared.actorUuid=null;change.prepared.actorName="SYSTEM";change.prepared.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,change.before);change.prepared.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,change.after);rows.add(change.prepared);}
                else rows.add(row(io?ActionType.CART_TRANSFER:ActionType.CART_CONTENT_CHANGE,io?"storage_io":"cargo",change.before,change.after,change.detail));
            }
            var participants=new LinkedHashMap<UUID,AbstractContraptionEntity>();
            participants.put(CreateContraptionSources.id(entity),entity);
            for(var change:changes)participants.putIfAbsent(CreateContraptionSources.id(change.entity),change.entity);
            // Each construction's own history contains the complete operation, including its peer.
            // Carriages of one train share the root history, but keep distinct storage addresses.
            for(var participant:participants.values()){
                var b=before.copy();var a=after.copy();
                for(var tag:new CompoundTag[]{b,a}){
                    tag.putUUID("Construction",CreateContraptionSources.id(participant));
                    tag.putString("ConstructionKind",CreateContraptionSources.kind(participant).name());
                    tag.putUUID("StorageEntity",participant.getUUID());tag.remove("Cart");
                    if(CreateCartAudit.state(participant)!=null)tag.putUUID("Cart",CreateCartAudit.state(participant).id);
                }
                var manifest=CreateCargoAudit.row(participant,ActionType.CART_TRANSFER,"automation_transfer",b,a,
                        "machine="+machine+"; changes="+changes.size()+"; external="+beforeSlots.size()+"; constructions="+participants.size());
                // An ambient player cause does not establish who started this machine's transfer.
                manifest.actorUuid=null;manifest.actorName="SYSTEM";
                rows.add(manifest);
            }
            if(changes.isEmpty()&&!unverifiedCreative.isEmpty()){
                var snapshot=new CompoundTag();snapshot.putInt("Format",1);snapshot.putString("Kind","actor_result");
                snapshot.putString("Operation","unknown_world_creative");snapshot.putBoolean("Complete",false);
                snapshot.put("UnverifiedCreativeIO",unverifiedCreative.copy());
                rows.add(row(ActionType.CART_TRANSFER,"actor_result",snapshot,snapshot.copy(),"unverified world creative IO"));
            }
            return List.copyOf(rows);
        }
        private List<LogEntry> worldCreativeRows(){
            var rows=new ArrayList<LogEntry>(worldCreative.size());
            for(var raw:worldCreative){
                var io=(CompoundTag)raw;var pos=BlockPos.of(io.getLong("Pos"));
                var snapshot=new CompoundTag();snapshot.putInt("Format",1);snapshot.putString("Kind","world_creative_io");
                snapshot.putBoolean("Complete",false);snapshot.put("IO",io.copy());
                var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.CART_TRANSFER;
                row.dim=io.getString("Dimension");row.x=pos.getX();row.y=pos.getY();row.z=pos.getZ();
                row.source="create:world_creative";row.actorName="SYSTEM";
                row.count=io.getCompound("Payload").getInt(io.getString("Kind").equals("item")?"count":"amount");
                row.extra="world creative "+io.getString("Direction")+" "+io.getString("Kind")+"; machine="+machine+"; no construction endpoint";
                if(io.getString("Kind").equals("item"))row.deferSnapshot(LogEntry.SnapshotField.ITEM,io.getCompound("Payload").copy());
                row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,snapshot);
                row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,snapshot.copy());
                LogIdGenerator.ensure(row);rows.add(row);
            }
            for(var raw:unverifiedCreative){
                var io=(CompoundTag)raw;var snapshot=new CompoundTag();snapshot.putInt("Format",1);
                snapshot.putString("Kind","world_creative_io");snapshot.putBoolean("Complete",false);snapshot.put("Unverified",io.copy());
                var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.CART_TRANSFER;
                row.dim=machineDim!=null?machineDim:entity!=null?entity.level().dimension().location().toString():"unknown";
                var pos=machinePos!=null?machinePos:entity!=null?entity.blockPosition():BlockPos.ZERO;
                row.x=pos.getX();row.y=pos.getY();row.z=pos.getZ();
                row.source="create:world_creative";row.actorName="SYSTEM";
                row.extra="world creative IO unresolved; machine="+machine+"; reason="+io.getString("Reason");
                row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,snapshot);
                row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,snapshot.copy());
                LogIdGenerator.ensure(row);rows.add(row);
            }
            return List.copyOf(rows);
        }
        public List<LogEntry> snapshotRows(boolean success){return rows(success);}
        private CompoundTag world(net.minecraft.world.entity.Entity item,CompoundTag original){
            var tag=new CompoundTag();tag.putUUID("WorldEntity",item.getUUID());
            tag.putString("Dimension",item.level().dimension().location().toString());tag.putString("EntityType",BuiltInRegistries.ENTITY_TYPE.getKey(item.getType()).toString());
            tag.putLong("Pos",original==null?item.blockPosition().asLong():original.getLong("Pos"));
            if(!item.isRemoved()){
                var stack=com.simibubi.create.foundation.item.ItemHelper.fromItemEntity(item);
                var nbt=NbtSerde.snapshotEntity((ServerLevel)item.level(),item);
                var value=NbtSerde.snapshotItemStack(stack,item.registryAccess());
                if(nbt==null||value==null||stack.isEmpty())complete=false;
                else {tag.put("EntityNBT",nbt);tag.put("Item",value);}
            }return tag;
        }
        private CompoundTag envelope(boolean complete,ListTag slots,boolean after){
            var n=new CompoundTag();n.putInt("Format",1);n.putString("Kind","automation");n.putUUID("Transfer",id);n.putUUID("Construction",CreateContraptionSources.id(entity));n.putString("ConstructionKind",CreateContraptionSources.kind(entity).name());n.putUUID("StorageEntity",entity.getUUID());if(CreateCartAudit.state(entity)!=null)n.putUUID("Cart",CreateCartAudit.state(entity).id);
            var cargo=new ListTag();
            for(int i=0;i<changes.size();i++){var value=(after?changes.get(i).after:changes.get(i).before).copy();value.putUUID("Transfer",id);value.putInt("TransferIndex",i);value.putString("TransferKind","automation");cargo.add(value);}
            n.put("Cargo",cargo);
            n.putInt("Changes",changes.size());n.putBoolean("Complete",complete);n.put("Slots",slots);
            n.putString("Machine",machine);n.putString("MachineDim",machineDim);n.putLong("MachinePos",machinePos.asLong());if(mounted)n.putBoolean("Mounted",true);return n;
        }
        private LogEntry row(ActionType type,String phase,CompoundTag before,CompoundTag after,String detail){
            var row=CreateCargoAudit.row(entity,type,phase,before,after,"automation; "+detail);
            row.actorUuid=null;row.actorName="SYSTEM";
            return row;
        }
        private void clear(){
            source=null;entity=null;id=null;machine=null;machineDim=null;machinePos=null;
            if(!changes.isEmpty())changes.clear();
            if(!external.isEmpty())external.clear();
            if(!addresses.isEmpty())addresses.clear();
            if(!directHandlers.isEmpty())directHandlers.clear();
            if(!worldBefore.isEmpty())worldBefore.clear();
            if(!worldCreative.isEmpty())worldCreative.clear();
            if(!unverifiedCreative.isEmpty())unverifiedCreative.clear();
        }
        public void finish(boolean success){
            current=null;boolean previousPublishing=publishing;publishing=true;
            try{if(mounted||cargoDelivery)for(var row:rows(success))CreateCargoAudit.deliver(entity,row);
                else if(publish&&CreateCartAudit.enabled()){
                    ServerLevel level=entity!=null?(ServerLevel)entity.level():source!=null&&source.getLevel() instanceof ServerLevel server?server:null;
                    if(level!=null&&(entity!=null&&(!changes.isEmpty()||!unverifiedCreative.isEmpty())||!worldCreative.isEmpty()||!unverifiedCreative.isEmpty()))for(var row:rows(success))LoggerRuntime.storage(level).append(row);
                }}
            catch(RuntimeException e){CreateCartAudit.failed("automation transfer",e);}
            finally{publishing=previousPublishing;clear();}
        }
    }
}

package com.roften.avilixlogger.compat.industry;

import com.roften.avilixlogger.compat.machines.MachineAudit;
import com.roften.avilixlogger.compat.teams.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.lang.ref.WeakReference;
import java.util.*;

/** Receipts for owned physical tanks. Native simulation is coalesced; external actions are immediate. */
public final class IndustrialFluids {
    private static final Map<Object, Binding> OWNERS = new WeakHashMap<>();
    private static final Set<Binding> PENDING = Collections.newSetFromMap(new IdentityHashMap<>());
    private IndustrialFluids() {}
    public static void bind(BlockEntity be) throws ReflectiveOperationException {
        IndustrialAudit.data(be).fluids.clear();
        String type=be.getClass().getSimpleName();
        Object storage = switch(type) {
            case "OilBarrelBlockEntity", "BulkFermenterBlockEntity", "DistillationTankBlockEntity", "ModularDieselEngineBlockEntity" -> StateAccess.field(be,"tankInventory");
            case "BlazeEnchanterBlockEntity", "ClassicBlazeEnchanterBlockEntity", "BlazeForgerBlockEntity" -> StateAccess.field(be,"tanks");
            case "GrindstoneDrainBlockEntity", "PrinterBlockEntity", "ExperienceLanternBlockEntity",
                 "DieselEngineBlockEntity", "HugeDieselEngineBlockEntity", "ChemicalTurretBlockEntity",
                 "PumpjackHoleBlockEntity", "BurnerBlockEntity", "CanisterBlockEntity" -> StateAccess.field(be,"tank");
            default -> null;
        };
        if (storage == null) return;
        if (storage instanceof IFluidHandler handler) add(be,handler,"tank");
        else if(storage.getClass().getName().equals("plus.dragons.createdragonsplus.common.fluids.tank.FluidTankBehaviour")){
            Object[] handlers=(Object[])StateAccess.call(storage,"getHandlers");
            if(handlers.length>64)throw new IllegalStateException("Physical tanks budget");
            for(int i=0;i<handlers.length;i++)add(be,(IFluidHandler)handlers[i],"tank:"+i);
        }else {
            Object[] segments=(Object[])StateAccess.call(storage,"getTanks");
            if(segments.length>64)throw new IllegalStateException("Physical tanks budget");
            for(int i=0;i<segments.length;i++)add(be,(IFluidHandler)StateAccess.field(segments[i],"tank"),"tank:"+i);
        }
    }
    private static void add(BlockEntity be,IFluidHandler handler,String name) throws ReflectiveOperationException {
        var old=OWNERS.get(handler);
        if(old!=null&&old.owner.get()==be){IndustrialAudit.data(be).fluids.add(old);return;}
        var binding=new Binding(be,handler,name); OWNERS.put(handler,binding);
        IndustrialAudit.data(be).fluids.add(binding);
        // The fermenter's delegate tanks are native physical members of this one handler.
        if(handler.getClass().getSimpleName().equals("BulkFermenterFluidHandler"))
            for(Object tank:(Collection<?>)StateAccess.field(handler,"tanks")) OWNERS.put(tank,binding);
    }
    public static Action begin(Object handler,boolean simulate) {
        var server=ServerLifecycleHooks.getCurrentServer();
        if(simulate||server==null||!server.isSameThread())return null;
        var binding=OWNERS.get(handler); if(binding==null||binding.active)return null;
        var be=binding.owner.get();
        if(be==null||!IndustrialAudit.handles(be)||!MachineAudit.policy(be,ActionType.MACHINE_CONTENT_CHANGE)
                ||ModStateAudit.capturing(be,"industrial"))return null;
        try {
            boolean batch=IndustrialAudit.simulating();
            if(!batch)flush(binding);
            var before=read(binding);
            binding.active=true; return new Action(binding,before,batch,server.getTickCount());
        } catch(RuntimeException|LinkageError failure){IndustrialAudit.fail("fluid-before");return null;}
    }
    private static Raw read(Binding binding) {
        var handler=binding.handler.get(); if(handler==null)throw new IllegalStateException("Detached physical handler");
        int size=handler.getTanks(); if(size<0||size>64)throw new IllegalStateException("Physical tanks budget");
        var fluids=new FluidStack[size];var capacities=new int[size];
        for(int i=0;i<size;i++){fluids[i]=handler.getFluidInTank(i).copy();capacities[i]=handler.getTankCapacity(i);}
        return new Raw(fluids,capacities);
    }
    private static boolean same(Raw first,Raw last) {
        if(!Arrays.equals(first.capacities,last.capacities)||first.fluids.length!=last.fluids.length)return false;
        for(int i=0;i<first.fluids.length;i++)if(!FluidStack.matches(first.fluids[i],last.fluids[i]))return false;
        return true;
    }
    private static CompoundTag tag(BlockEntity be,Binding binding,Raw raw) {
        var tag=MachineAudit.location(be);tag.putString("PhysicalTank",binding.name);var tanks=new ListTag();
        for(int i=0;i<raw.fluids.length;i++){var tank=new CompoundTag();tank.putInt("Tank",i);tank.putInt("Capacity",raw.capacities[i]);
            tank.put("Fluid",save(be,raw.fluids[i]));tanks.add(tank);}
        tag.put("Tanks",tanks);return tag;
    }
    private static Tag save(BlockEntity be,FluidStack fluid){return fluid.isEmpty()?new CompoundTag():fluid.save(be.getLevel().registryAccess(),new CompoundTag());}
    public static ListTag snapshot(BlockEntity be) {
        var result=new ListTag();
        for(var binding:IndustrialAudit.data(be).fluids)result.add(tag(be,binding,read(binding)));
        return result;
    }
    public static void flushOwner(BlockEntity be) {
        for(var binding:IndustrialAudit.data(be).fluids)flush(binding);
    }
    public static void due(Object value) {
        if(PENDING.isEmpty())return;
        if(!(value instanceof BlockEntity be)||IndustrialAudit.provider(be)==null||!(be.getLevel() instanceof ServerLevel level)||!level.getServer().isSameThread())return;
        int tick=level.getServer().getTickCount();
        for(var binding:IndustrialAudit.data(be).fluids)if(binding.pending!=null&&tick-binding.pending.start>=20)flush(binding);
    }
    private static void flush(Binding binding) {
        var pending=binding.pending; if(pending==null)return;binding.pending=null;PENDING.remove(binding);
        var be=binding.owner.get();if(be==null||!(be.getLevel() instanceof ServerLevel level))return;
        if(!ModStateAudit.enabled(level.getServer(),ActionType.MACHINE_CONTENT_CHANGE))return;
        try(var cause=CauseContext.unattributed()){
            var before=tag(be,binding,pending.before);var after=tag(be,binding,pending.after);
            after.putInt("FromServerTick",pending.start);after.putInt("ToServerTick",pending.end);after.putInt("NativeMutations",pending.operations);
            after.putBoolean("NativeThrew",pending.threw);after.put("ObservedFlows",flows(be,pending.flows));
            // A circulation may return to its original amount; its observed operation count remains evidence.
            ModStateAudit.receipt(level.getServer(),IndustrialAudit.provider(be),"native-fluid-interval",ActionType.MACHINE_CONTENT_CHANGE,before,after);
        }catch(RuntimeException|LinkageError failure){IndustrialAudit.fail("fluid-flush");}
    }
    public static void stop(MinecraftServer server) {
        if(!server.isSameThread())return;
        for(var binding:new ArrayList<>(PENDING))flush(binding);
        PENDING.clear();OWNERS.clear();
    }
    public static final class Binding {
        private final WeakReference<BlockEntity> owner; private final WeakReference<IFluidHandler> handler; private final String name;
        private boolean active;private Pending pending;
        private Binding(BlockEntity be,IFluidHandler handler,String name){owner=new WeakReference<>(be);this.handler=new WeakReference<>(handler);this.name=name;}
    }
    private static final class Flow {
        private final FluidStack key;private long in,out;
        private Flow(FluidStack stack){key=stack.copy();key.setAmount(1);}
    }
    private static void flow(List<Flow> flows,FluidStack stack,long amount){
        if(stack.isEmpty()||amount==0)return;
        Flow found=null;for(var flow:flows)if(FluidStack.isSameFluidSameComponents(flow.key,stack)){found=flow;break;}
        if(found==null){found=new Flow(stack);flows.add(found);}
        if(amount>0)found.in+=amount;else found.out-=amount;
    }
    private static void delta(List<Flow> flows,Raw first,Raw last){
        for(int i=0;i<Math.max(first.fluids.length,last.fluids.length);i++){
            var a=i<first.fluids.length?first.fluids[i]:FluidStack.EMPTY;var b=i<last.fluids.length?last.fluids[i]:FluidStack.EMPTY;
            if(!a.isEmpty()&&!b.isEmpty()&&FluidStack.isSameFluidSameComponents(a,b))flow(flows,b,(long)b.getAmount()-a.getAmount());
            else {flow(flows,a,-(long)a.getAmount());flow(flows,b,b.getAmount());}
        }
    }
    private static ListTag flows(BlockEntity be,List<Flow> flows){
        var values=new ListTag();for(var flow:flows){var tag=new CompoundTag();tag.put("FluidKey",save(be,flow.key));tag.putLong("InMB",flow.in);tag.putLong("OutMB",flow.out);values.add(tag);}return values;
    }
    private record Raw(FluidStack[] fluids,int[] capacities){}
    private static final class Pending {
        private final Raw before;private Raw after;private final List<Flow> flows=new ArrayList<>();private final int start;private int end,operations;private boolean threw;
        private Pending(Raw before,Raw after,int tick){this.before=before;this.after=after;start=end=tick;operations=1;delta(flows,before,after);}
    }
    public static final class Action {
        private final Binding binding;private final Raw before;private final boolean batch;private final int tick;
        private Action(Binding binding,Raw before,boolean batch,int tick){this.binding=binding;this.before=before;this.batch=batch;this.tick=tick;}
        public void finish(boolean threw) {
            try {
                var after=read(binding);if(same(before,after))return;
                var be=binding.owner.get();if(be==null||!(be.getLevel() instanceof ServerLevel level))return;
                if(batch){
                    var pending=binding.pending;
                    // Do not merge across an unobserved raw edit.
                    if(pending!=null&&(!same(pending.after,before)||pending.flows.size()>128)){flush(binding);pending=null;}
                    if(pending==null){
                        if(PENDING.size()>=1024){
                            try(var cause=CauseContext.unattributed()){emit(be,before,after,threw);}return;
                        }
                        pending=new Pending(before,after,tick);binding.pending=pending;PENDING.add(binding);
                    }else{pending.after=after;pending.end=tick;pending.operations++;delta(pending.flows,before,after);}
                    pending.threw|=threw;
                    if(threw)flush(binding);
                }else emit(be,before,after,threw);
            }catch(RuntimeException|LinkageError failure){IndustrialAudit.fail("fluid-after");}
            finally{binding.active=false;}
        }
        private void emit(BlockEntity be,Raw before,Raw after,boolean threw){
            var first=tag(be,binding,before);var last=tag(be,binding,after);last.putBoolean("NativeThrew",threw);
            var flow=new ArrayList<Flow>();delta(flow,before,after);last.put("ObservedFlows",flows(be,flow));
            ModStateAudit.receipt(((ServerLevel)be.getLevel()).getServer(),IndustrialAudit.provider(be),"physical-fluid-change",ActionType.MACHINE_CONTENT_CHANGE,first,last);
        }
    }
}

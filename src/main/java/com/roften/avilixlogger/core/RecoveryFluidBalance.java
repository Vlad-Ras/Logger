package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Detached accounting for ordinary Create tanks. A multiblock controller owns the fluid once. */
public final class RecoveryFluidBalance {
    public record Limits(int capacityPerBlock,int maxHeight){public static final Limits NONE=new Limits(0,0);}
    private record Address(String dimension,BlockPos pos) {}
    private final Map<Address,RollbackEngine.ReplayCheckpoint> before=new HashMap<>(),after=new HashMap<>();
    private final Map<CompoundTag,Long> balance=new HashMap<>();
    private final long limit;
    private final BooleanSupplier cancelled;
    private final Limits limits;
    private long bytes;
    private RecoveryFluidBalance(Limits limits,long limit,BooleanSupplier cancelled){this.limits=limits;this.limit=limit;this.cancelled=cancelled;}
    public static void verify(List<RecoveryItemBalance.Input> inputs,Limits limits,long limit,BooleanSupplier cancelled) {
        var check=new RecoveryFluidBalance(limits,limit,cancelled);
        for(var input:inputs) {
            check.cancel();check.charge(160);
            var address=new Address(input.dimension(),input.pos());
            if(input.before()==null||input.after()==null||input.before().state()==null||input.after().state()==null)throw check.problem("replay_fluid_snapshot_required",address);
            if(check.before.putIfAbsent(address,input.before())!=null||check.after.putIfAbsent(address,input.after())!=null)
                throw check.problem("replay_fluid_duplicate_position",address);
        }
        check.add(check.before,-1);check.add(check.after,1);
        for(var entry:check.balance.entrySet())if(entry.getValue()!=0)
            throw new IllegalStateException("replay_fluid_resource_graph_required: "+entry.getKey().getString("id")
                    +"; изменение жидкости="+entry.getValue()+" мБ; нужны связанные снимки внешних участников; мир не изменён");
    }
    private void add(Map<Address,RollbackEngine.ReplayCheckpoint> states,int sign) {
        for(var entry:states.entrySet()) {
            cancel();var address=entry.getKey();var checkpoint=entry.getValue();var be=checkpoint.be();
            String block=checkpoint.state().getString("Name"),id=be==null?"":be.getString("id");
            if(block.equals("create:creative_fluid_tank")||id.equals("create:creative_fluid_tank"))
                throw problem("replay_creative_fluid_contract_required",address);
            if(!block.equals("create:fluid_tank")&&!id.equals("create:fluid_tank"))continue;
            if(limits.capacityPerBlock()<=0||limits.maxHeight()<=0)throw problem("replay_fluid_adapter_unavailable",address);
            if(!block.equals("create:fluid_tank")||!id.equals("create:fluid_tank"))throw problem("replay_fluid_snapshot_required",address);
            ordinary(be,address);
            if(be.contains("Controller")) {
                var root=new Address(address.dimension(),controller(be,address));
                if(root.equals(address)||!states.containsKey(root))throw problem("replay_fluid_controller_outside_scene",address);
                var owner=states.get(root).be();
                if(owner==null||!owner.getString("id").equals("create:fluid_tank")||owner.contains("Controller")||!inside(root,owner,address.pos()))
                    throw problem("replay_fluid_controller_conflict",address);
                if(be.contains("TankContent")||be.contains("Size")||be.contains("Height"))throw problem("replay_fluid_alias_conflict",address);
                continue;
            }
            int width=dimension(be,"Size",address),height=dimension(be,"Height",address);
            long count=Math.multiplyExact(Math.multiplyExact((long)width,width),height);
            if(count>states.size())throw problem("replay_fluid_multiblock_incomplete",address);
            long capacity=Math.multiplyExact(count,limits.capacityPerBlock());
            if(capacity>Integer.MAX_VALUE||(long)address.pos().getX()+width-1>Integer.MAX_VALUE
                    ||(long)address.pos().getZ()+width-1>Integer.MAX_VALUE||(long)address.pos().getY()+height-1>Integer.MAX_VALUE)
                throw problem("replay_fluid_invalid_dimensions",address);
            for(int x=0;x<width;x++)for(int z=0;z<width;z++)for(int y=0;y<height;y++) {
                cancel();var pos=address.pos().offset(x,y,z);var part=states.get(new Address(address.dimension(),pos));
                if(part==null||part.be()==null||!part.state().getString("Name").equals("create:fluid_tank")||!part.be().getString("id").equals("create:fluid_tank"))
                    throw problem("replay_fluid_multiblock_incomplete",address);
                if(!pos.equals(address.pos())&&(!part.be().contains("Controller")||!controller(part.be(),address).equals(address.pos())))
                    throw problem("replay_fluid_controller_conflict",address);
            }
            if(!be.contains("TankContent",Tag.TAG_COMPOUND))throw problem("replay_fluid_snapshot_required",address);
            var tank=be.getCompound("TankContent");
            if(tank.isEmpty())continue;
            if(tank.size()!=1||!tank.contains("Fluid",Tag.TAG_COMPOUND))throw problem("replay_invalid_fluid_stack",address);
            var fluid=tank.getCompound("Fluid");var key=ResourceLocation.tryParse(fluid.getString("id"));
            if(key==null||fluid.getString("id").equals("minecraft:empty")||!fluid.contains("amount",Tag.TAG_INT)||fluid.getInt("amount")<=0
                    ||fluid.contains("components")&&!fluid.contains("components",Tag.TAG_COMPOUND))throw problem("replay_invalid_fluid_stack",address);
            if(fluid.getInt("amount")>capacity)throw problem("replay_fluid_capacity_exceeded",address);
            charge(192L+fluid.sizeInBytes());var identity=fluid.copy();identity.remove("amount");
            balance.merge(identity,Math.multiplyExact((long)fluid.getInt("amount"),sign),Math::addExact);
        }
    }
    private boolean inside(Address root,CompoundTag owner,BlockPos pos) {
        int width=dimension(owner,"Size",root),height=dimension(owner,"Height",root);
        return (long)pos.getX()-root.pos().getX()>=0&&(long)pos.getX()-root.pos().getX()<width
                &&(long)pos.getZ()-root.pos().getZ()>=0&&(long)pos.getZ()-root.pos().getZ()<width
                &&(long)pos.getY()-root.pos().getY()>=0&&(long)pos.getY()-root.pos().getY()<height;
    }
    private int dimension(CompoundTag be,String key,Address address) {
        if(!be.contains(key,Tag.TAG_INT)||be.getInt(key)<1||be.getInt(key)>(key.equals("Size")?3:limits.maxHeight()))throw problem("replay_fluid_invalid_dimensions",address);
        return be.getInt(key);
    }
    private BlockPos controller(CompoundTag be,Address address) {
        if(!be.contains("Controller",Tag.TAG_INT_ARRAY)||be.getIntArray("Controller").length!=3)throw problem("replay_fluid_invalid_controller",address);
        var xyz=be.getIntArray("Controller");return new BlockPos(xyz[0],xyz[1],xyz[2]);
    }
    private void ordinary(CompoundTag be,Address address) {
        if(be.contains("Uninitialized"))throw problem("replay_fluid_connectivity_pending",address);
        if(!be.contains("Boiler",Tag.TAG_COMPOUND))throw problem("replay_fluid_snapshot_required",address);
        var boiler=be.getCompound("Boiler");
        if(!boiler.contains("Supply",Tag.TAG_FLOAT)||!boiler.contains("ActiveHeat",Tag.TAG_INT)||!boiler.contains("PassiveHeat",Tag.TAG_BYTE)
                ||!boiler.contains("Engines",Tag.TAG_INT)||!boiler.contains("Whistles",Tag.TAG_INT)||!boiler.contains("Update",Tag.TAG_BYTE))
            throw problem("replay_fluid_snapshot_required",address);
        if(boiler.getFloat("Supply")!=0||boiler.getInt("ActiveHeat")!=0||boiler.getBoolean("PassiveHeat")
                ||boiler.getInt("Engines")!=0||boiler.getInt("Whistles")!=0)
            throw problem("replay_boiler_resource_graph_required",address);
    }
    private void cancel(){if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();}
    private void charge(long amount){bytes=Math.addExact(bytes,amount);if(bytes>limit)throw new IllegalStateException("Проверка жидкости превышает оставшийся лимит памяти; мир не изменён");}
    private IllegalStateException problem(String reason,Address address){
        String description=switch(reason) {
            case "replay_fluid_snapshot_required" -> "Нет полного снимка бака";
            case "replay_fluid_multiblock_incomplete" -> "В сцену попали не все части ёмкости";
            case "replay_fluid_controller_outside_scene" -> "Контроллер ёмкости находится вне сцены";
            case "replay_fluid_controller_conflict" -> "Части ёмкости ссылаются на разные контроллеры";
            case "replay_fluid_alias_conflict" -> "Жидкость записана одновременно у контроллера и дочерней части";
            case "replay_fluid_invalid_controller" -> "Повреждены координаты контроллера";
            case "replay_fluid_invalid_dimensions" -> "Размеры бака не соответствуют настройкам Create";
            case "replay_fluid_capacity_exceeded" -> "Объём жидкости превышает ёмкость бака";
            case "replay_invalid_fluid_stack" -> "Повреждены тип, количество или компоненты жидкости";
            case "replay_fluid_connectivity_pending" -> "Create ещё не завершил объединение ёмкости; повторите после его завершения";
            case "replay_boiler_resource_graph_required" -> "Паровой котёл требует связанных снимков производственных участников";
            case "replay_creative_fluid_contract_required" -> "Креативный бак требует отдельного подтверждения бесконечного источника";
            case "replay_fluid_adapter_unavailable" -> "Совместимый Create или его настройки недоступны";
            default -> "Противоречивые данные ёмкости";
        };
        return new IllegalStateException(reason+": "+description+"; "+address.dimension()+" "+address.pos()+"; мир не изменён");
    }
}

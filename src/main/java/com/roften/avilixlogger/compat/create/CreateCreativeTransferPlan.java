package com.roften.avilixlogger.compat.create;

import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Detached, explicit generator/sink contracts. A template is a reservation, never a finite delta. */
public final class CreateCreativeTransferPlan {
    public static final String ITEM="com.simibubi.create.content.logistics.crate.CreativeCrateMountedStorage";
    public static final String FLUID="com.simibubi.create.content.fluids.tank.storage.creative.CreativeFluidTankMountedStorage";
    public record IO(CompoundTag address) {}
    private CreateCreativeTransferPlan() {}
    public static IO prepare(CompoundTag before,CompoundTag after) {
        validate(before,after);return new IO(after.copy());
    }
    private static void validate(CompoundTag before,CompoundTag after) {
        var b=before.copy();var a=after.copy();b.remove("Payload");a.remove("Payload");
        String kind=after.getString("ResourceKind"),direction=after.getString("Direction"),quantity=kind.equals("item")?"count":"amount";
        var payload=after.getCompound("Payload");var supplied=after.getCompound("Supplied");
        if(!b.equals(a)||before.contains("Payload")||!after.getString("Kind").equals("storage_io")||after.getInt("IOFormat")!=1
                ||!after.getBoolean("Infinite")||!after.getBoolean("External")||after.getBoolean("BaseCart")||after.getBoolean("Incomplete")
                ||!Set.of("item","fluid").contains(kind)||!Set.of("in","out").contains(direction)||!after.contains("LocalPos",4)
                ||!after.contains("Slot",3)||!after.contains("Size",3)||!after.contains("Limit",3)||after.getInt("Limit")<=0
                ||!after.getString("Handler").equals(kind.equals("item")?ITEM:FLUID)||after.getInt("Size")!=(kind.equals("item")?2:1)
                ||after.getInt("Slot")<0||after.getInt("Slot")>=after.getInt("Size")||payload.getString("id").isBlank()||payload.getInt(quantity)<=0
                ||!supplied.isEmpty()&&(supplied.getString("id").isBlank()||supplied.getInt(quantity)<=0))
            throw new IllegalStateException("Нет полного проверенного creative supply/void контракта");
        if(direction.equals("out")){
            var expected=supplied.copy();var actual=payload.copy();expected.remove(quantity);actual.remove(quantity);
            if(supplied.isEmpty()||!expected.equals(actual)||payload.getInt(quantity)>supplied.getInt(quantity)||after.getInt("Slot")!=0)
                throw new IllegalStateException("Выданный ресурс не соответствует creative-шаблону");
        }
    }
    public static boolean observed(CompoundTag before,CompoundTag after){try{validate(before,after);return true;}catch(RuntimeException rejected){return false;}}

    /** Pure native take/return only. Item use, block conversion and fluid/bucket conversion need other contracts. */
    public static void validateHand(CompoundTag before,CompoundTag after,List<CreateTransferRollbackPlan.CargoUndo> cargo,List<IO> creative){
        validateHand(before,after,cargo,creative,new net.minecraft.nbt.ListTag());
    }
    public static void validateHand(CompoundTag before,CompoundTag after,List<CreateTransferRollbackPlan.CargoUndo> cargo,List<IO> creative,net.minecraft.nbt.ListTag uses){
        String operation=creative.getFirst().address().getString("Operation");
        if(!Set.of("deployer_take","deployer_return").contains(operation))throw new IllegalStateException("Нет отдельного native take/return контракта creative IO");
        var balance=new HashMap<CompoundTag,Long>();
        for(var raw:before.getList("Inventory",10))addItem(balance,(CompoundTag)raw,-1);
        for(var raw:after.getList("Inventory",10))addItem(balance,(CompoundTag)raw,1);
        for(var raw:uses){var use=(CompoundTag)raw;for(var item:use.getList("Before",10))addItem(balance,(CompoundTag)item,1);for(var item:use.getList("After",10))addItem(balance,(CompoundTag)item,-1);}
        for(var cell:cargo){
            if(!cell.address().getString("Kind").equals("item")||!cell.address().getString("Operation").equals(operation))throw new IllegalStateException("В creative обмене руки обнаружен другой путь груза");
            addItem(balance,cell.before(),-1);addItem(balance,cell.after(),1);
        }
        for(var io:creative){var address=io.address();
            if(!address.getString("ResourceKind").equals("item")||!address.getString("Operation").equals(operation)||!address.getString("Direction").equals(operation.equals("deployer_take")?"out":"in"))throw new IllegalStateException("Creative IO не соответствует направлению обмена руки");
            addItem(balance,address.getCompound("Payload"),operation.equals("deployer_take")?-1:1);
        }
        if(balance.values().stream().anyMatch(count->count!=0))throw new IllegalStateException("Рука, груз и creative IO не совпадают по количеству или компонентам");
    }
    private static void addItem(Map<CompoundTag,Long> balance,CompoundTag value,int sign){
        if(value.isEmpty())return;
        if(value.getString("id").isBlank()||!value.contains("count",3)||value.getInt("count")<=0)throw new IllegalStateException("Некорректный предмет creative обмена руки");
        var key=value.copy();key.remove("count");key.remove("Slot");balance.merge(key,(long)sign*value.getInt("count"),Long::sum);
    }
}

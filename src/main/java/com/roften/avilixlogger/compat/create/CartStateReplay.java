package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorage;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.util.*;

/** Detached forward replay. Never touches world blocks, external handlers or recipients. */
public final class CartStateReplay {
    private final CompoundTag item,data;
    private final HolderLookup.Provider registries;
    private final Map<Long,CompoundTag> blocks=new HashMap<>();
    private int applied;
    public CartStateReplay(CompoundTag item,HolderLookup.Provider registries){
        this.item=item.copy();this.registries=registries;data=this.item.getCompound("components").getCompound("create:minecart_contraption_data");
        for(var raw:data.getCompound("Blocks").getList("BlockList",10)){
            var block=(CompoundTag)raw;if(blocks.put(block.getLong("Pos"),block)!=null)throw new IllegalStateException("Повторная позиция блока");
        }
    }
    public CompoundTag item(){return item;}
    public int applied(){return applied;}
    public void apply(LogEntry row){
        try{
            var before=NbtSerde.fromSnbt(row.beBefore);var after=NbtSerde.fromSnbt(row.beAfter);
            if(row.type==ActionType.CART_CONTENT_CHANGE){cargo(before,after);applied++;}
            else if(row.type==ActionType.CART_STRUCTURE_CHANGE){structure(before,after);applied++;}
            else if(row.type==ActionType.CART_STATUS){
                if(CartAuditContext.phase(row.source).equals("interaction"))require(after!=null&&after.contains("DisabledActors",9),"Нет сохранённых настроек управления");
                if(after!=null&&after.contains("DisabledActors",9)){data.put("DisabledActors",after.get("DisabledActors").copy());applied++;}
            }
        }catch(RuntimeException e){throw new IllegalStateException("Не удалось восстановить состояние на entry="+row.id+": "+e.getMessage(),e);}
    }
    private void structure(CompoundTag before,CompoundTag after){
        require(before!=null&&after!=null&&before.contains("LocalPos",4)&&after.contains("State",10)&&before.getLong("LocalPos")==after.getLong("LocalPos"),"Неполное изменение блока конструкции");
        var block=blocks.get(after.getLong("LocalPos"));require(block!=null,"Нет исходного блока для изменения");
        var palette=data.getCompound("Blocks").getList("Palette",10);
        require(before.contains("State",10)&&palette.getCompound(block.getInt("State")).equals(before.getCompound("State"))&&Objects.equals(block.get("Data"),before.get("Data")),"Цепочка состояний блока разорвана");
        // Existing setBlock audits do not capture creation/removal of mounted storages or actors.
        require(before.getCompound("State").getString("Name").equals(after.getCompound("State").getString("Name")),"Замена типа механизма требует нового полного снимка");
        var state=after.getCompound("State");int index=palette.indexOf(state);if(index<0){index=palette.size();palette.add(state.copy());}
        block.putInt("State",index);if(after.contains("Data",10))block.put("Data",after.get("Data").copy());else block.remove("Data");
        block.remove("UpdateTag");
    }
    private void cargo(CompoundTag before,CompoundTag after){
        boolean old=CartAuditContext.restoring(true);
        try{cargoDetached(before,after);}finally{CartAuditContext.restoring(old);}
    }
    private void cargoDetached(CompoundTag before,CompoundTag after){
        require(registries!=null,"Для содержимого необходимы реестры сервера");
        require(before!=null&&after!=null&&!before.getBoolean("Incomplete")&&!after.getBoolean("Incomplete"),"Неполные данные содержимого");
        String kind=after.getString("Kind");require(kind.equals(before.getString("Kind"))&&Set.of("item","fluid").contains(kind),"Неизвестный вид содержимого");
        require(before.contains("LocalPos",4)&&after.contains("LocalPos",4)&&before.getLong("LocalPos")==after.getLong("LocalPos")&&before.getBoolean("BaseCart")==after.getBoolean("BaseCart"),"Адрес содержимого изменился");
        require(!after.getBoolean("BaseCart"),"Содержимое базовой вагонетки требует полного снимка");
        var list=data.getList(kind.equals("item")?"items":"fluids",10);CompoundTag entry=null;
        for(var raw:list){var candidate=(CompoundTag)raw;if(NBTHelper.readBlockPos(candidate,"pos").asLong()==after.getLong("LocalPos")){require(entry==null,"Повторный адрес хранилища");entry=candidate;}}
        require(entry!=null&&entry.contains("storage",10),"Нет полного исходного хранилища");
        var ops=registries.createSerializationContext(NbtOps.INSTANCE);
        if(kind.equals("item")){
            require(before.contains("Slot",3)&&after.contains("Slot",3)&&before.getInt("Slot")==after.getInt("Slot"),"Адрес слота изменился");
            var storage=MountedItemStorage.CODEC.parse(ops,entry.get("storage")).getOrThrow();
            require(storage instanceof com.simibubi.create.api.contraption.storage.item.simple.SimpleMountedStorage,"Тип хранилища не поддерживает изменение снимка");
            var encoded=MountedItemStorage.CODEC.encodeStart(ops,storage).getOrThrow();require(encoded.equals(entry.get("storage")),"Кодек хранилища теряет сохранённые данные");
            int slot=after.getInt("Slot");require(slot>=0&&slot<storage.getSlots(),"Неверный слот");
            var b=CartRecoveryContents.item(registries,before,"Item");var a=CartRecoveryContents.item(registries,after,"Item");
            require(ItemStack.matches(storage.getStackInSlot(slot),b),"Цепочка содержимого слота разорвана");
            require(a.isEmpty()||a.getCount()<=storage.getSlotLimit(slot),"Превышена ёмкость слота");
            storage.setStackInSlot(slot,a.copy());require(ItemStack.matches(storage.getStackInSlot(slot),a),"Хранилище отклонило содержимое");
            entry.put("storage",MountedItemStorage.CODEC.encodeStart(ops,storage).getOrThrow());
        }else{
            require(before.contains("Tank",3)&&after.contains("Tank",3)&&before.getInt("Tank")==after.getInt("Tank"),"Адрес бака изменился");
            var storage=MountedFluidStorage.CODEC.parse(ops,entry.get("storage")).getOrThrow();
            require(storage.getClass()==com.simibubi.create.content.fluids.tank.storage.FluidTankMountedStorage.class&&storage.getTanks()==1&&after.getInt("Tank")==0,"Тип бака не поддерживает изменение снимка");
            require(MountedFluidStorage.CODEC.encodeStart(ops,storage).getOrThrow().equals(entry.get("storage")),"Кодек бака теряет сохранённые данные");
            var b=CartRecoveryContents.fluid(registries,before,"Fluid");var a=CartRecoveryContents.fluid(registries,after,"Fluid");
            require(FluidStack.matches(storage.getFluidInTank(0),b),"Цепочка содержимого бака разорвана");
            require(a.getAmount()<=storage.getTankCapacity(0),"Превышена ёмкость бака");
            storage.drain(Integer.MAX_VALUE,IFluidHandler.FluidAction.EXECUTE);
            require(a.isEmpty()||storage.fill(a.copy(),IFluidHandler.FluidAction.EXECUTE)==a.getAmount(),"Бак отклонил жидкость");
            require(FluidStack.matches(storage.getFluidInTank(0),a),"Содержимое бака изменилось при восстановлении");
            entry.put("storage",MountedFluidStorage.CODEC.encodeStart(ops,storage).getOrThrow());
        }
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}

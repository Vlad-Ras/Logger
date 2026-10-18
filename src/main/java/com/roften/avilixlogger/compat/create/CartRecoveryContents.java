package com.roften.avilixlogger.compat.create;

import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorage;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import java.util.*;

/** Strict, world-free codec round trips: a failed or partial inventory is never issued. */
public final class CartRecoveryContents {
    public record Summary(int itemStorages,int fluidStorages,long items,long fluidAmount){}
    private CartRecoveryContents(){}
    public static ItemStack item(HolderLookup.Provider registries,CompoundTag tag,String key){
        if(!tag.contains(key))return ItemStack.EMPTY;
        if(!tag.contains(key,10))throw new IllegalStateException("Повреждён предмет");
        var result=ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE),tag.get(key)).getOrThrow();
        if(result.isEmpty())throw new IllegalStateException("Сохранённый предмет прочитан как пустой");return result;
    }
    public static FluidStack fluid(HolderLookup.Provider registries,CompoundTag tag,String key){
        if(!tag.contains(key))return FluidStack.EMPTY;
        if(!tag.contains(key,10))throw new IllegalStateException("Повреждена жидкость");
        var result=FluidStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE),tag.get(key)).getOrThrow();
        if(result.isEmpty())throw new IllegalStateException("Сохранённая жидкость прочитана как пустая");return result;
    }
    public static Summary validate(CompoundTag item,HolderLookup.Provider registries){
        boolean old=com.roften.avilixlogger.core.CartAuditContext.restoring(true);
        try{return validateDetached(item,registries);}finally{com.roften.avilixlogger.core.CartAuditContext.restoring(old);}
    }
    private static Summary validateDetached(CompoundTag item,HolderLookup.Provider registries){
        var ops=registries.createSerializationContext(NbtOps.INSTANCE);var data=item.getCompound("components").getCompound("create:minecart_contraption_data");
        var positions=new HashSet<Long>();for(var raw:data.getCompound("Blocks").getList("BlockList",10))positions.add(((CompoundTag)raw).getLong("Pos"));
        for(var raw:data.getCompound("Blocks").getList("Palette",10)){
            var state=(CompoundTag)raw;var key=net.minecraft.resources.ResourceLocation.tryParse(state.getString("Name"));
            if(key==null||!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(key))throw new IllegalStateException("Отсутствует блок из снимка: "+state.getString("Name"));
        }
        for(String key:List.of("items","fluids"))if(data.contains(key)&&(!data.contains(key,9)||((ListTag)data.get(key)).getElementType()!=10&&!((ListTag)data.get(key)).isEmpty()))throw new IllegalStateException("Повреждён список хранилищ: "+key);
        if(!data.getList("Storage",10).isEmpty()||!data.getList("FluidStorage",10).isEmpty())throw new IllegalStateException("Старый формат хранилища требует нового полного снимка");
        for(var raw:data.getList("DisabledActors",10))ItemStack.OPTIONAL_CODEC.parse(ops,raw).getOrThrow();
        long items=0,fluids=0;int itemStores=0,fluidStores=0;
        for(String key:List.of("items","fluids")){
            var seen=new HashSet<Long>();
            for(var raw:data.getList(key,10)){
                var entry=(CompoundTag)raw;long pos=NBTHelper.readBlockPos(entry,"pos").asLong();
                if(!entry.contains("pos")||!seen.add(pos)||!positions.contains(pos)||!entry.contains("storage",10))throw new IllegalStateException("Повреждён адрес хранилища: "+key+" "+pos);
                if(key.equals("items")){
                    var source=MountedItemStorage.CODEC.parse(ops,entry.get("storage")).getOrThrow();
                    var encoded=MountedItemStorage.CODEC.encodeStart(ops,source).getOrThrow();
                    if(!encoded.equals(entry.get("storage")))throw new IllegalStateException("Кодек изменяет сохранённые данные хранилища");
                    var restored=MountedItemStorage.CODEC.parse(ops,encoded).getOrThrow();
                    if(source.getSlots()!=restored.getSlots()||source.getSlots()>100000)throw new IllegalStateException("Изменился размер хранилища");
                    for(int slot=0;slot<source.getSlots();slot++){
                        var a=source.getStackInSlot(slot);var b=restored.getStackInSlot(slot);
                        if(!ItemStack.matches(a,b))throw new IllegalStateException("Кодек потерял предмет/компоненты в слоте "+slot);
                        items+=a.getCount();
                    }itemStores++;
                }else{
                    var source=MountedFluidStorage.CODEC.parse(ops,entry.get("storage")).getOrThrow();
                    var encoded=MountedFluidStorage.CODEC.encodeStart(ops,source).getOrThrow();
                    if(!encoded.equals(entry.get("storage")))throw new IllegalStateException("Кодек изменяет сохранённые данные бака");
                    var restored=MountedFluidStorage.CODEC.parse(ops,encoded).getOrThrow();
                    if(source.getTanks()!=restored.getTanks()||source.getTanks()>100000)throw new IllegalStateException("Изменилось число баков");
                    for(int tank=0;tank<source.getTanks();tank++){
                        var a=source.getFluidInTank(tank);var b=restored.getFluidInTank(tank);
                        if(!FluidStack.matches(a,b)||source.getTankCapacity(tank)!=restored.getTankCapacity(tank))throw new IllegalStateException("Кодек потерял жидкость/ёмкость бака");
                        fluids+=a.getAmount();
                    }fluidStores++;
                }
            }
        }
        return new Summary(itemStores,fluidStores,items,fluids);
    }
}

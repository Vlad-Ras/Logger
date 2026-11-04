package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/** Real supply/destruction in creative stores; the supplied template never becomes a fake cargo delta. */
public final class CreateInfiniteStorageAudit {
    private CreateInfiniteStorageAudit() {}
    public static void item(CartStorageAudit.Ref ref, int slot, ItemStack supplied, ItemStack transferred, boolean inbound) {
        var entity=ref==null?null:ref.entity().get();
        if(entity==null||transferred.isEmpty()||!CreateCargoAudit.active(entity))return;
        var before=envelope(ref,"item",slot,inbound);var after=before.copy();
        var template=NbtSerde.snapshotItemStack(supplied,entity.registryAccess());
        var payload=NbtSerde.snapshotItemStack(transferred,entity.registryAccess());
        if(template!=null){before.put("Supplied",template);after.put("Supplied",template.copy());}
        if(payload==null)after.putBoolean("Incomplete",true);else after.put("Payload",payload);
        record(ref,before,after);
    }
    public static void fluid(CartStorageAudit.Ref ref, FluidStack supplied, FluidStack transferred, boolean inbound) {
        var entity=ref==null?null:ref.entity().get();
        if(entity==null||transferred.isEmpty()||!CreateCargoAudit.active(entity))return;
        var before=envelope(ref,"fluid",0,inbound);var after=before.copy();
        if(!supplied.isEmpty()){before.put("Supplied",supplied.save(entity.registryAccess()));after.put("Supplied",supplied.save(entity.registryAccess()));}
        after.put("Payload",transferred.save(entity.registryAccess()));record(ref,before,after);
    }
    private static CompoundTag envelope(CartStorageAudit.Ref ref,String resource,int slot,boolean inbound){
        var tag=new CompoundTag();tag.putInt("Format",1);tag.putString("Kind","storage_io");tag.putString("ResourceKind",resource);
        tag.putLong("LocalPos",ref.pos().asLong());tag.putInt("Slot",slot);tag.putBoolean("Infinite",true);
        tag.putBoolean("External",true);tag.putBoolean("Complete",false);tag.putString("Direction",inbound?"in":"out");
        var entity=ref.entity().get();Object handler=entity==null||entity.getContraption()==null?null:resource.equals("item")?entity.getContraption().getStorage().getAllItemStorages().get(ref.pos()):entity.getContraption().getStorage().getFluids().storages.get(ref.pos());
        if(!ref.base()&&handler!=null&&handler.getClass().getName().equals(resource.equals("item")?CreateCreativeTransferPlan.ITEM:CreateCreativeTransferPlan.FLUID)){
            tag.putInt("IOFormat",1);tag.putString("Handler",handler.getClass().getName());
            if(handler instanceof net.neoforged.neoforge.items.IItemHandler items){tag.putInt("Size",items.getSlots());tag.putInt("Limit",items.getSlotLimit(slot));}
            else if(handler instanceof net.neoforged.neoforge.fluids.capability.IFluidHandler fluids){tag.putInt("Size",fluids.getTanks());tag.putInt("Limit",fluids.getTankCapacity(0));}
        }else tag.putBoolean("Incomplete",true);
        return tag;
    }
    private static void record(CartStorageAudit.Ref ref,CompoundTag before,CompoundTag after){
        var entity=ref.entity().get();
        if(CartAutomationAudit.captureInfinite(ref,before,after))return;
        if(CreateCargoAudit.capture(entity,ActionType.CART_TRANSFER,"storage_io",before,after,"creative storage supply/void"))return;
        CreateCargoAudit.emit(entity,ActionType.CART_TRANSFER,"storage_io",before,after,"creative storage supply/void");
    }
}

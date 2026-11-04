package com.roften.avilixlogger.compat.backpacked;
import com.roften.avilixlogger.core.ActionType;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;

/** Detached sparse slot snapshots in the same format as the GUI's container details. */
public record BackpackSlotDelta(CompoundTag before,CompoundTag after,ActionType type) {
    public static BackpackSlotDelta capture(ItemStack[] old,ItemStack[] now,HolderLookup.Provider registries){
        var b=new ListTag();var a=new ListTag();
        for(int i=0;i<Math.max(old.length,now.length);i++){
            ItemStack previous=i<old.length?old[i]:ItemStack.EMPTY;ItemStack next=i<now.length?now[i]:ItemStack.EMPTY;
            if(ItemStack.matches(previous,next))continue;
            CompoundTag bn=previous.isEmpty()?new CompoundTag():(CompoundTag)previous.save(registries);bn.putInt("Slot",i);b.add(bn);
            CompoundTag an=next.isEmpty()?new CompoundTag():(CompoundTag)next.save(registries);an.putInt("Slot",i);a.add(an);
        }
        if(b.isEmpty())return null;
        ActionType type=ActionType.BACKPACK_CONTENT_CHANGE;
        if(b.size()==1){int slot=b.getCompound(0).getInt("Slot");ItemStack previous=slot<old.length?old[slot]:ItemStack.EMPTY;ItemStack next=slot<now.length?now[slot]:ItemStack.EMPTY;
            if(previous.isEmpty()||(!next.isEmpty()&&ItemStack.isSameItemSameComponents(previous,next)&&next.getCount()>previous.getCount()))type=ActionType.BACKPACK_PUT;
            else if(next.isEmpty()||(ItemStack.isSameItemSameComponents(previous,next)&&next.getCount()<previous.getCount()))type=ActionType.BACKPACK_TAKE;
        }
        var before=new CompoundTag();before.putInt("Size",Math.max(old.length,now.length));before.put("Items",b);
        var after=new CompoundTag();after.putInt("Size",Math.max(old.length,now.length));after.put("Items",a);
        return new BackpackSlotDelta(before,after,type);
    }
}

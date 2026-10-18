package com.roften.avilixlogger.compat.backpacked.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mrcrayfish.backpacked.blockentity.ShelfBlockEntity;
import com.roften.avilixlogger.compat.backpacked.BackpackAudit;
import com.roften.avilixlogger.core.ActionType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(ShelfBlockEntity.ShelfContainer.class)
public abstract class BackpackShelfMixin {
    @WrapMethod(method="setItem")
    private void avilixlogger$set(int slot,ItemStack stack,Operation<Void> original){
        var container=(ShelfBlockEntity.ShelfContainer)(Object)this;var shelf=container.getShelf();
        if(!BackpackAudit.enabled()||!(shelf.getLevel() instanceof ServerLevel level)){original.call(slot,stack);return;}
        var old=container.getItem(slot).copy();
        BackpackAudit.ensure(new BackpackAudit.Ref(stack,level,shelf.getBlockPos(),null,"shelf",slot,null),null,ActionType.BACKPACK_DISCOVER);
        if(BackpackAudit.isBag(old))BackpackAudit.lifecycle(new BackpackAudit.Ref(old,level,shelf.getBlockPos(),null,"shelf",slot,null),null,ActionType.BACKPACK_REMOVE,"shelf_remove");
        original.call(slot,stack);
        if(BackpackAudit.isBag(stack))BackpackAudit.lifecycle(new BackpackAudit.Ref(stack,level,shelf.getBlockPos(),null,"shelf",slot,null),null,ActionType.BACKPACK_PLACE,"shelf_place");
    }
    @WrapMethod(method="removeItem")
    private ItemStack avilixlogger$remove(int slot,int count,Operation<ItemStack> original){
        var container=(ShelfBlockEntity.ShelfContainer)(Object)this;var shelf=container.getShelf();
        if(BackpackAudit.enabled()&&shelf.getLevel() instanceof ServerLevel level&&count>0){var stack=container.getItem(slot);if(BackpackAudit.isBag(stack))BackpackAudit.lifecycle(new BackpackAudit.Ref(stack,level,shelf.getBlockPos(),null,"shelf",slot,null),null,ActionType.BACKPACK_REMOVE,"shelf_remove");}
        return original.call(slot,count);
    }
}

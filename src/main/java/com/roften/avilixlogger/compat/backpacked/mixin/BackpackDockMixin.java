package com.roften.avilixlogger.compat.backpacked.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mrcrayfish.backpacked.blockentity.BackpackDockBlockEntity;
import com.roften.avilixlogger.compat.backpacked.BackpackAudit;
import com.roften.avilixlogger.core.ActionType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(BackpackDockBlockEntity.class)
public abstract class BackpackDockMixin {
    @WrapMethod(method="setBackpackOrPop")
    private boolean avilixlogger$place(ItemStack stack,Operation<Boolean> original){
        var dock=(BackpackDockBlockEntity)(Object)this;
        if(!BackpackAudit.enabled()||!(dock.getLevel() instanceof ServerLevel level))return original.call(stack);
        ItemStack old=dock.getBackpackWithContents();
        var ref=new BackpackAudit.Ref(stack,level,dock.getBlockPos(),null,"dock",-1,null);BackpackAudit.ensure(ref,null,ActionType.BACKPACK_DISCOVER);
        if(BackpackAudit.isBag(old))BackpackAudit.lifecycle(new BackpackAudit.Ref(old,level,dock.getBlockPos(),null,"dock",-1,null),null,ActionType.BACKPACK_REMOVE,"dock_pop");
        boolean result=original.call(stack);
        if(result&&dock.hasBackpack()&&old.isEmpty())BackpackAudit.lifecycle(new BackpackAudit.Ref(dock.getBackpackWithContents(),level,dock.getBlockPos(),null,"dock",-1,null),null,ActionType.BACKPACK_PLACE,"dock_place");
        return result;
    }
}

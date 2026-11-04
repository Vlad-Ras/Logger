package com.roften.avilixlogger.compat.backpacked.mixin;
import com.mrcrayfish.backpacked.blockentity.*;
import com.roften.avilixlogger.compat.backpacked.BackpackAudit;
import com.roften.avilixlogger.core.ActionType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(BlockEntity.class)
public abstract class BackpackBlockLifecycleMixin {
    private void avilixlogger$capture(ActionType type){
        if(!BackpackAudit.enabled())return;BlockEntity be=(BlockEntity)(Object)this;
        if(!(be.getLevel() instanceof ServerLevel level))return;
        ItemStack stack=be instanceof BackpackDockBlockEntity dock?dock.getBackpack():be instanceof ShelfBlockEntity shelf?shelf.getBackpack():ItemStack.EMPTY;
        if(!BackpackAudit.isBag(stack))return;
        var ref=new BackpackAudit.Ref(stack,level,be.getBlockPos(),null,be instanceof BackpackDockBlockEntity?"dock":"shelf",-1,null);
        BackpackAudit.ensure(ref,null,ActionType.BACKPACK_DISCOVER);
        if(be instanceof BackpackDockBlockEntity dock)ref=new BackpackAudit.Ref(dock.getBackpackWithContents(),level,be.getBlockPos(),null,"dock",-1,null);
        BackpackAudit.lifecycle(ref,null,type,type.name());
    }
    @Inject(method="setLevel",at=@At("RETURN"))
    private void avilixlogger$load(CallbackInfo ci){avilixlogger$capture(ActionType.BACKPACK_LOAD);}
    @Inject(method="setRemoved",at=@At("HEAD"))
    private void avilixlogger$unload(CallbackInfo ci){avilixlogger$capture(ActionType.BACKPACK_UNLOAD);}
}

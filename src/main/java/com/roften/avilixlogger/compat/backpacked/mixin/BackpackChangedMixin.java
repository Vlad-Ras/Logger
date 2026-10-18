package com.roften.avilixlogger.compat.backpacked.mixin;
import com.mrcrayfish.backpacked.inventory.BackpackInventory;
import com.mrcrayfish.backpacked.blockentity.*;
import com.roften.avilixlogger.compat.backpacked.BackpackAudit;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin({BackpackInventory.class,BackpackDockBlockEntity.ItemStackContainer.class,ShelfBlockEntity.BackpackShelfContainer.class})
public abstract class BackpackChangedMixin {
    @Inject(method="<init>",at=@At("RETURN"))
    private void avilixlogger$init(CallbackInfo ci){BackpackAudit.initialize((Container)this);}
    @Inject(method="setChanged",at=@At("RETURN"))
    private void avilixlogger$dirty(CallbackInfo ci){BackpackAudit.changed((Container)this);}
}

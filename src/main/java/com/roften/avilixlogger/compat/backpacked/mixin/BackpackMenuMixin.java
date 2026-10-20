package com.roften.avilixlogger.compat.backpacked.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.backpacked.BackpackAudit;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(AbstractContainerMenu.class)
public abstract class BackpackMenuMixin {
    @WrapMethod(method="clicked")
    private void avilixlogger$click(int slot,int button,ClickType type,Player player,Operation<Void> original){
        try(var scope=BackpackAudit.scope(player,"menu:"+type)){original.call(slot,button,type,player);}
    }
}

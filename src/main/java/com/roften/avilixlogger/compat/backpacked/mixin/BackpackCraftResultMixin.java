package com.roften.avilixlogger.compat.backpacked.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.backpacked.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.*;
@Mixin(ResultSlot.class)
public abstract class BackpackCraftResultMixin {
    @Shadow @Final private Player player;
    @WrapMethod(method="remove")
    private ItemStack avilixlogger$craft(int amount,Operation<ItemStack> original){
        var slot=(ResultSlot)(Object)this;
        if(amount>0&&BackpackAudit.enabled()&&player instanceof ServerPlayer p&&BackpackAudit.isBag(slot.getItem())){
            var index=BackpackIndex.get(p.server);if(index!=null)index.identify(slot.getItem(),p.getUUID(),p.getGameProfile().getName());
        }
        return original.call(amount);
    }
}

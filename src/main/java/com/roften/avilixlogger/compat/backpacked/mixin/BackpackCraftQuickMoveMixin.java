package com.roften.avilixlogger.compat.backpacked.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.backpacked.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
@Mixin({CraftingMenu.class,InventoryMenu.class})
public abstract class BackpackCraftQuickMoveMixin {
    @WrapMethod(method="quickMoveStack")
    private ItemStack avilixlogger$craft(Player player,int slot,Operation<ItemStack> original){
        var menu=(AbstractContainerMenu)(Object)this;
        if(slot==0&&BackpackAudit.enabled()&&player instanceof ServerPlayer p&&BackpackAudit.isBag(menu.slots.getFirst().getItem())){
            var index=BackpackIndex.get(p.server);if(index!=null)index.identify(menu.slots.getFirst().getItem(),p.getUUID(),p.getGameProfile().getName());
        }
        return original.call(player,slot);
    }
}

package com.roften.avilixlogger.compat.backpacked.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mrcrayfish.backpacked.BackpackHelper;
import com.roften.avilixlogger.compat.backpacked.BackpackAudit;
import com.roften.avilixlogger.core.ActionType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(BackpackHelper.class)
public abstract class BackpackHelperMixin {
    @WrapMethod(method="equipBackpack")
    private static boolean avilixlogger$equipFromItem(Player player,ItemStack stack,Operation<Boolean> original){
        if(!BackpackAudit.enabled()||!(player instanceof ServerPlayer p))return original.call(player,stack);
        BackpackAudit.ensure(new BackpackAudit.Ref(stack,p.serverLevel(),p.blockPosition(),p,"inventory",-1,null),p,ActionType.BACKPACK_DISCOVER);
        boolean result=original.call(player,stack);
        if(result)BackpackAudit.trackPlayer(p,ActionType.BACKPACK_EQUIP);return result;
    }
    @WrapMethod(method="removeAllBackpacks")
    private static net.minecraft.core.NonNullList<ItemStack> avilixlogger$removeAll(Player player,Operation<net.minecraft.core.NonNullList<ItemStack>> original){
        if(!BackpackAudit.enabled()||!(player instanceof ServerPlayer p))return original.call(player);
        BackpackAudit.trackPlayer(p,ActionType.BACKPACK_TRANSFER);
        var result=original.call(player);
        for(int i=0;i<result.size();i++){ItemStack stack=result.get(i);if(BackpackAudit.isBag(stack))BackpackAudit.lifecycle(new BackpackAudit.Ref(stack,p.serverLevel(),p.blockPosition(),p,"equipped",i,null),null,ActionType.BACKPACK_UNEQUIP,"remove_all_backpacks");}
        return result;
    }
    @WrapMethod(method="setBackpackStack")
    private static boolean avilixlogger$equip(Player player,ItemStack stack,int index,Operation<Boolean> original){
        if(!BackpackAudit.enabled()||!(player instanceof ServerPlayer p))return original.call(player,stack,index);
        var before=BackpackHelper.getBackpackStack(player,index);
        var incoming=new BackpackAudit.Ref(stack,p.serverLevel(),p.blockPosition(),p,"equipped",index,null);
        BackpackAudit.ensure(incoming,player,ActionType.BACKPACK_DISCOVER);
        ItemStack old=before.copy();boolean ok=original.call(player,stack,index);
        if(ok){
            if(BackpackAudit.isBag(old)&&!net.minecraft.world.item.ItemStack.matches(old,stack))BackpackAudit.lifecycle(new BackpackAudit.Ref(old,p.serverLevel(),p.blockPosition(),p,"equipped",index,null),null,ActionType.BACKPACK_UNEQUIP,"equipment_removed");
            if(BackpackAudit.isBag(stack))BackpackAudit.lifecycle(incoming,null,ActionType.BACKPACK_EQUIP,"equipment_added");
        }
        return ok;
    }
}

package com.roften.avilixlogger.compat.dieselgenerators.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.*;
import com.roften.avilixlogger.compat.industry.*;

@Pseudo
@Mixin(targets = "com.jesz.createdieselgenerators.content.track_layers_bag.TrackLayersBagItem", remap = false)
public abstract class TrackBagMixin {
    @WrapMethod(method = "useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;", remap = false)
    private net.minecraft.world.InteractionResult avilixlogger$place(net.minecraft.world.item.context.UseOnContext context, Operation<net.minecraft.world.InteractionResult> original){
        var action=IndustrialPlayers.begin(context.getPlayer(),"createdieselgenerators","track-bag-place",context.getItemInHand(),false); boolean success=false;
        try{var result=original.call(context);success=true;return result;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;", remap = false)
    private net.minecraft.world.InteractionResultHolder<ItemStack> avilixlogger$use(Level level, Player player, net.minecraft.world.InteractionHand hand, Operation<net.minecraft.world.InteractionResultHolder<ItemStack>> original){
        var action=IndustrialPlayers.begin(player,"createdieselgenerators","track-bag-use",player.getItemInHand(hand),false); boolean success=false;
        try{var result=original.call(level, player, hand);if(action!=null)action.result(result);success=true;return result;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "overrideStackedOnOther(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/inventory/Slot;Lnet/minecraft/world/inventory/ClickAction;Lnet/minecraft/world/entity/player/Player;)Z", remap = false)
    private boolean avilixlogger$insert(ItemStack stack, net.minecraft.world.inventory.Slot slot, net.minecraft.world.inventory.ClickAction click, Player player, Operation<Boolean> original){
        var action=IndustrialPlayers.begin(player,"createdieselgenerators","track-bag-slot",stack,false); boolean success=false;
        try{var result=original.call(stack, slot, click, player);success=true;return result;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "overrideOtherStackedOnMe(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/inventory/Slot;Lnet/minecraft/world/inventory/ClickAction;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/SlotAccess;)Z", remap = false)
    private boolean avilixlogger$extract(ItemStack bag, ItemStack other, net.minecraft.world.inventory.Slot slot, net.minecraft.world.inventory.ClickAction click, Player player, net.minecraft.world.entity.SlotAccess access, Operation<Boolean> original){
        var action=IndustrialPlayers.begin(player,"createdieselgenerators","track-bag-extract",bag,false); boolean success=false;
        try{var result=original.call(bag, other, slot, click, player, access);success=true;return result;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method="select(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/item/ItemStack;)Z",remap=false)
    private static boolean avilixlogger$select(net.minecraft.world.level.LevelAccessor world,BlockPos pos,net.minecraft.world.phys.Vec3 look,ItemStack stack,Operation<Boolean> original){
        var action=IndustrialPlayers.stack(stack,world instanceof ServerLevel level?new IndustrialPlayers.Site(level,pos):null);boolean success=false;
        try{boolean result=original.call(world,pos,look,stack);success=true;return result;}finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method="clearSelection(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/InteractionResultHolder;",remap=false)
    private static net.minecraft.world.InteractionResultHolder<ItemStack> avilixlogger$clear(ItemStack stack,Level level,Player player,Operation<net.minecraft.world.InteractionResultHolder<ItemStack>> original){
        var action=IndustrialPlayers.begin(player,"createdieselgenerators","track-bag-clear-selection",stack,false);boolean success=false;
        try{var result=original.call(stack,level,player);if(action!=null)action.result(result);success=true;return result;}finally{if(action!=null)action.finish(!success);}
    }
}

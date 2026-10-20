package com.roften.avilixlogger.compat.backpacked.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mrcrayfish.backpacked.common.augment.AugmentHandler;
import com.roften.avilixlogger.compat.backpacked.BackpackAudit;
import net.minecraft.server.level.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(AugmentHandler.class)
public abstract class BackpackAugmentsMixin {
    @WrapMethod(method="recallBackpack")
    private static boolean avilixlogger$recall(ServerPlayer p,int index,net.minecraft.world.item.ItemStack stack,com.mrcrayfish.backpacked.common.augment.impl.RecallAugment augment,Operation<Boolean> original){
        if(!BackpackAudit.enabled())return original.call(p,index,stack,augment);
        BackpackAudit.ensure(new BackpackAudit.Ref(stack,p.serverLevel(),p.blockPosition(),p,"equipped",index,null),p,com.roften.avilixlogger.core.ActionType.BACKPACK_DISCOVER);
        var copy=stack.copy();boolean result=original.call(p,index,stack,augment);
        if(result&&augment.shelfKey().isPresent()){
            var key=augment.shelfKey().get();var level=p.server.getLevel(key.level());
            if(level!=null)BackpackAudit.lifecycle(new BackpackAudit.Ref(copy,level,BlockPos.of(key.position()),null,"recall_queue",index,null),p,com.roften.avilixlogger.core.ActionType.BACKPACK_UPGRADE_USE,"augment:recall_queued");
        }
        return result;
    }
    @WrapMethod(method="beforeItemPickup")
    private static boolean avilixlogger$funnel(Player p,ItemEntity e,UUID target,Operation<Boolean> original){try(var s=BackpackAudit.scope(p,"augment:funnelling")){return original.call(p,e,target);}}
    @WrapMethod(method="beforeArrowPickup")
    private static boolean avilixlogger$arrows(Player p,AbstractArrow arrow,Operation<Boolean> original){try(var s=BackpackAudit.scope(p,"augment:funnelling_arrow")){return original.call(p,arrow);}}
    @WrapMethod(method={"onLootDroppedByEntity","onLootDroppedByBlock"})
    private static void avilixlogger$loot(Collection<ItemEntity> drops,Player p,Operation<Void> original){try(var s=BackpackAudit.scope(p,"augment:lootbound")){original.call(drops,p);}}
    @WrapMethod(method="onPlayerPickupExperienceOrb")
    private static void avilixlogger$repair(Player p,ExperienceOrb orb,Operation<Void> original){try(var s=BackpackAudit.scope(p,"augment:reforge")){original.call(p,orb);}}
    @WrapMethod(method="onPlayerChangedBlockPos")
    private static void avilixlogger$walk(ServerPlayer p,ServerLevel level,BlockPos pos,int brightness,Operation<Void> original){try(var s=BackpackAudit.scope(p,"augment:lightweaver")){original.call(p,level,pos,brightness);}}
    @WrapMethod(method="onPlayerWalkOnCrops")
    private static void avilixlogger$seedflow(ServerPlayer p,Operation<Void> original){try(var s=BackpackAudit.scope(p,"augment:seedflow")){original.call(p);}}
    @WrapMethod(method="replantCrop")
    private static void avilixlogger$plant(BlockState state,ServerPlayer p,BlockPos pos,Operation<Void> original){try(var s=BackpackAudit.scope(p,"augment:farmhand")){original.call(state,p,pos);}}
}

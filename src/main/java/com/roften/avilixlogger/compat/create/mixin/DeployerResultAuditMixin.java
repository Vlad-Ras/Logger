package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.roften.avilixlogger.compat.create.CreateDeployerAudit;
import com.simibubi.create.content.kinetics.deployer.DeployerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Mark results inside Create's actual handler; PASS/cancellation is not a successful use. */
@Mixin(value = DeployerHandler.class, remap = false)
public abstract class DeployerResultAuditMixin {
    @WrapOperation(method = "activateInner", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/deployer/DeployerHandler;safeOnUse(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"), require = 1)
    private static InteractionResult avilixlogger$block(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
        var result = original.call(state, level, pos, player, hand, hit);
        CreateDeployerAudit.used("block_use", result.consumesAction(), null); return result;
    }
    @WrapOperation(method = "activateInner", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"), require = 1)
    private static InteractionResult avilixlogger$item(ItemStack stack, UseOnContext context, Operation<InteractionResult> original) {
        var result = original.call(stack, context); CreateDeployerAudit.used("item_use", result.consumesAction(), null); return result;
    }
    @WrapOperation(method = "activateInner", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;onItemUseFirst(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"), require = 1)
    private static InteractionResult avilixlogger$itemFirst(ItemStack stack, UseOnContext context, Operation<InteractionResult> original) {
        var result = original.call(stack, context); CreateDeployerAudit.used("item_first", result.consumesAction(), null); return result;
    }
    @WrapOperation(method = "activateInner", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/Item;use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;"), require = 1)
    private static InteractionResultHolder<ItemStack> avilixlogger$itemUse(Item item, Level level, Player player, InteractionHand hand, Operation<InteractionResultHolder<ItemStack>> original) {
        var result = original.call(item, level, player, hand); CreateDeployerAudit.used("item_use", result.getResult().consumesAction(), null); return result;
    }
    @WrapOperation(method = "activateInner", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"), require = 1)
    private static InteractionResult avilixlogger$entity(Entity entity, Player player, InteractionHand hand, Operation<InteractionResult> original) {
        if(com.roften.avilixlogger.core.CartAuditContext.rollbackActive&&!com.roften.avilixlogger.core.CartAuditContext.restoring()&&com.roften.avilixlogger.core.CartAuditContext.LOCKED_ENTITIES.contains(entity.getUUID()))return InteractionResult.FAIL;
        com.roften.avilixlogger.compat.create.CreateDeployerTransaction.entityBefore(entity);
        var result = original.call(entity, player, hand); CreateDeployerAudit.used("entity_use", result.consumesAction(), entity); return result;
    }
    @WrapOperation(method = "activateInner", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;interactLivingEntity(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"), require = 1)
    private static InteractionResult avilixlogger$entityItem(ItemStack stack, Player player, LivingEntity entity, InteractionHand hand, Operation<InteractionResult> original) {
        if(com.roften.avilixlogger.core.CartAuditContext.rollbackActive&&!com.roften.avilixlogger.core.CartAuditContext.restoring()&&com.roften.avilixlogger.core.CartAuditContext.LOCKED_ENTITIES.contains(entity.getUUID()))return InteractionResult.FAIL;
        com.roften.avilixlogger.compat.create.CreateDeployerTransaction.entityBefore(entity);
        var result = original.call(stack, player, entity, hand); CreateDeployerAudit.used("entity_use", result.consumesAction(), entity); return result;
    }
    @WrapOperation(method = "activateInner", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/deployer/DeployerFakePlayer;attack(Lnet/minecraft/world/entity/Entity;)V"), require = 1)
    private static void avilixlogger$attack(com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer player, Entity entity, Operation<Void> original) {
        com.roften.avilixlogger.compat.create.CreateDeployerTransaction.entityBefore(entity);
        float before = entity instanceof LivingEntity living ? living.getHealth() : 0;
        float absorption = entity instanceof LivingEntity living ? living.getAbsorptionAmount() : 0;
        var motion = entity.getDeltaMovement(); boolean alive = entity.isAlive();
        try { original.call(player, entity); } finally { CreateDeployerAudit.attacked(entity, before, absorption, motion, alive); }
    }
    @WrapOperation(method = "activateInner", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/deployer/DeployerHandler;tryHarvestBlock(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/level/ServerPlayerGameMode;Lnet/minecraft/core/BlockPos;)Z"), require = 1)
    private static boolean avilixlogger$harvest(ServerPlayer player, ServerPlayerGameMode mode, BlockPos pos, Operation<Boolean> original) {
        boolean result = original.call(player, mode, pos); CreateDeployerAudit.used("harvest", result, null); return result;
    }
    @WrapOperation(method = "activateInner", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;finishUsingItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;"), require = 1)
    private static ItemStack avilixlogger$finishUse(ItemStack stack, Level level, LivingEntity recipient, Operation<ItemStack> original) {
        if (!CreateDeployerAudit.capturing()) return original.call(stack, level, recipient);
        boolean self = recipient instanceof Player player && com.roften.avilixlogger.core.CreateActorContext.isTechnicalPlayer(player);
        if(!self){
            com.roften.avilixlogger.compat.create.CreateDeployerTransaction.entityBefore(recipient);
            if(!(recipient instanceof ServerPlayer)||recipient instanceof net.neoforged.neoforge.common.util.FakePlayer)
                com.roften.avilixlogger.compat.create.CreateDeployerTransaction.unsupported("Неизвестный получатель пищи");
        }
        var result = original.call(stack, level, recipient);
        CreateDeployerAudit.used(self ? "item_use" : recipient instanceof Player ? "player_feed" : "entity_use",true,self ? null : recipient);
        return result;
    }

}

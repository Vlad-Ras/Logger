package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.CauseContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;

/** Scope covers the actual action, including mod code, and closes on every exit/exception. */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeCauseMixin {
    @Shadow @Final protected ServerPlayer player;

    @WrapMethod(method = "destroyBlock")
    private boolean avilixlogger$destroy(BlockPos pos, Operation<Boolean> original) {
        try (var scope = CauseContext.push(player, CauseContext.Kind.BREAK_BLOCK, pos, player.getMainHandItem())) {
            return original.call(pos);
        }
    }
    @WrapMethod(method = "useItem")
    private InteractionResult avilixlogger$use(ServerPlayer actor, Level level, ItemStack stack,
                                               InteractionHand hand, Operation<InteractionResult> original) {
        try (var scope = CauseContext.push(actor, CauseContext.Kind.USE_ITEM, actor.blockPosition(), stack)) {
            return original.call(actor, level, stack, hand);
        }
    }
    @WrapMethod(method = "useItemOn")
    private InteractionResult avilixlogger$useOn(ServerPlayer actor, Level level, ItemStack stack,
                                                 InteractionHand hand, BlockHitResult hit,
                                                 Operation<InteractionResult> original) {
        try (var scope = CauseContext.push(actor, CauseContext.Kind.USE_BLOCK,
                hit == null ? actor.blockPosition() : hit.getBlockPos(), stack)) {
            return original.call(actor, level, stack, hand, hit);
        }
    }
}

package com.roften.avilixlogger.mixin;

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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

/** Keeps the real player cause alive around the complete vanilla/modded use and break call. */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeCauseMixin {
    @Shadow @Final protected ServerPlayer player;

    @Unique
    private Deque<CauseContext.Scope> avilixlogger$causeScopes;

    @Inject(method = "destroyBlock", at = @At("HEAD"), require = 0)
    private void avilixlogger$beforeDestroy(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        avilixlogger$push(CauseContext.push(player, CauseContext.Kind.BREAK_BLOCK, pos, player.getMainHandItem()));
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"), require = 0)
    private void avilixlogger$afterDestroy(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        avilixlogger$pop();
    }

    @Inject(method = "useItem", at = @At("HEAD"), require = 0)
    private void avilixlogger$beforeUseItem(ServerPlayer actionPlayer, Level level, ItemStack stack,
                                            InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        avilixlogger$push(CauseContext.push(actionPlayer, CauseContext.Kind.USE_ITEM,
                actionPlayer.blockPosition(), stack));
    }

    @Inject(method = "useItem", at = @At("RETURN"), require = 0)
    private void avilixlogger$afterUseItem(ServerPlayer actionPlayer, Level level, ItemStack stack,
                                           InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        avilixlogger$pop();
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), require = 0)
    private void avilixlogger$beforeUseOn(ServerPlayer actionPlayer, Level level, ItemStack stack,
                                          InteractionHand hand, BlockHitResult hit,
                                          CallbackInfoReturnable<InteractionResult> cir) {
        avilixlogger$push(CauseContext.push(actionPlayer, CauseContext.Kind.USE_BLOCK,
                hit == null ? actionPlayer.blockPosition() : hit.getBlockPos(), stack));
    }

    @Inject(method = "useItemOn", at = @At("RETURN"), require = 0)
    private void avilixlogger$afterUseOn(ServerPlayer actionPlayer, Level level, ItemStack stack,
                                         InteractionHand hand, BlockHitResult hit,
                                         CallbackInfoReturnable<InteractionResult> cir) {
        avilixlogger$pop();
    }

    @Unique
    private void avilixlogger$push(CauseContext.Scope scope) {
        if (scope == null) return;

        // Mixin does not guarantee that an instance field initializer is copied into every
        // target constructor. Initialise lazily so interactions can never fail with an NPE.
        if (avilixlogger$causeScopes == null) {
            avilixlogger$causeScopes = new ArrayDeque<>();
        }
        avilixlogger$causeScopes.addLast(scope);
    }

    @Unique
    private void avilixlogger$pop() {
        if (avilixlogger$causeScopes == null) return;
        CauseContext.Scope scope = avilixlogger$causeScopes.pollLast();
        if (scope != null) scope.close();
    }
}

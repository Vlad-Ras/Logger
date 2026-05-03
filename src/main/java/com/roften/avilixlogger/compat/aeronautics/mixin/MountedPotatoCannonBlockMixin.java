package com.roften.avilixlogger.compat.aeronautics.mixin;

import com.roften.avilixlogger.compat.aeronautics.AeronauticsCompatHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "dev.eriksonn.aeronautics.content.blocks.mounted_potato_cannon.MountedPotatoCannonBlock")
public class MountedPotatoCannonBlockMixin {
    @Inject(method = "useItemOn", at = @At("RETURN"), require = 0)
    private void avilixlogger$rememberLoader(ItemStack heldItem,
                                             BlockState blockState,
                                             Level level,
                                             BlockPos blockPos,
                                             Player player,
                                             InteractionHand interactionHand,
                                             BlockHitResult blockHitResult,
                                             CallbackInfoReturnable<ItemInteractionResult> cir) {
        try {
            if (!(level instanceof ServerLevel sl)) return;
            if (!(player instanceof ServerPlayer sp)) return;
            ItemInteractionResult result = cir.getReturnValue();
            if (result == null) return;
            String resultName = result.name();
            if ("PASS_TO_DEFAULT_BLOCK_INTERACTION".equals(resultName) || "FAIL".equals(resultName)) return;
            AeronauticsCompatHooks.notePlayerBlockAction(sl, sp, blockPos, heldItem, "mounted_potato_cannon_load");
        } catch (Throwable ignored) {}
    }
}

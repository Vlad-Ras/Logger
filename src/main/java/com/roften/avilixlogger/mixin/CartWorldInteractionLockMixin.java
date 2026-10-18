package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.CartRestoreLocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;

@Mixin(ServerPlayerGameMode.class)
public abstract class CartWorldInteractionLockMixin {
    @Shadow @Final protected ServerPlayer player;
    @WrapMethod(method="destroyBlock")
    private boolean avilixlogger$break(BlockPos pos,Operation<Boolean> original){
        if(CartRestoreLocks.blockLocked(player.level(),pos))return false;
        return original.call(pos);
    }
    @WrapMethod(method="useItemOn")
    private InteractionResult avilixlogger$use(ServerPlayer actor,Level level,ItemStack stack,InteractionHand hand,BlockHitResult hit,Operation<InteractionResult> original){
        if(hit!=null && (CartRestoreLocks.blockLocked(level,hit.getBlockPos()) || CartRestoreLocks.blockLocked(level,hit.getBlockPos().relative(hit.getDirection()))))return InteractionResult.FAIL;
        return original.call(actor,level,stack,hand,hit);
    }
}

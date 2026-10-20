package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.CauseContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;

/** Captures the real action beyond the pre-action NeoForge event. */
@Mixin(Player.class)
public abstract class PlayerEntityActionCauseMixin {
    @WrapMethod(method = "attack")
    private void avilixlogger$attack(Entity target, Operation<Void> original) {
        if(com.roften.avilixlogger.core.CartAuditContext.rollbackActive&&!com.roften.avilixlogger.core.CartAuditContext.restoring()&&com.roften.avilixlogger.core.CartAuditContext.LOCKED_ENTITIES.contains(target.getUUID()))return;
        Player player = (Player) (Object) this;
        try (var scope = CauseContext.push(player, CauseContext.Kind.ATTACK_ENTITY,
                target.blockPosition(), player.getMainHandItem())) {
            original.call(target);
        }
    }
    @WrapMethod(method = "interactOn")
    private InteractionResult avilixlogger$interact(Entity target, InteractionHand hand,
                                                   Operation<InteractionResult> original) {
        if(com.roften.avilixlogger.core.CartAuditContext.rollbackActive&&!com.roften.avilixlogger.core.CartAuditContext.restoring()&&com.roften.avilixlogger.core.CartAuditContext.LOCKED_ENTITIES.contains(target.getUUID()))return InteractionResult.FAIL;
        Player player = (Player) (Object) this;
        try (var scope = CauseContext.push(player, CauseContext.Kind.INTERACT_ENTITY,
                target.blockPosition(), player.getItemInHand(hand))) {
            return original.call(target, hand);
        }
    }
}

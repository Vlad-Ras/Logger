package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.mounted.CartAssemblerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = CartAssemblerBlockEntity.class, remap = false)
public abstract class CartAssemblerAuditMixin {
    @WrapMethod(method = "assemble")
    private void avilixlogger$assemble(Level world, BlockPos pos, AbstractMinecart cart, Operation<Void> original) {
        if (!(world instanceof ServerLevel level) || !cart.getPassengers().isEmpty() || !CreateCartAudit.enabled()) { original.call(world, pos, cart); return; }
        CartAuditState state = CreateCartAudit.forAssembly(level, pos, cart);
        CartAuditState previousAssembly = CreateCartAudit.assembling(state);
        var previous = CartAuditContext.enter(state.stamp("assemble"));
        try {
            original.call(world, pos, cart);
            var entity = CreateCartAudit.passenger(cart);
            if (entity != null && level.getEntity(entity.getUUID()) == entity) {
                CreateCartAudit.attach(entity);
                CreateCartAudit.emit(entity, ActionType.CART_ASSEMBLE, "assemble", CreateCartAudit.form(entity, "blocks"), CreateCartAudit.snapshot(entity, null), "assembled");
            }
        } finally { CartAuditContext.restore(previous); CreateCartAudit.assembling(previousAssembly); }
    }
}

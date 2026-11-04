package com.roften.avilixlogger.compat.accessories.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.accessories.AccessoriesAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

@Pseudo
@Mixin(targets = "io.wispforest.accessories.impl.AccessoriesHolderImpl", remap = false)
public abstract class HolderMixin {
    @WrapMethod(method = "init(Lio/wispforest/accessories/api/AccessoriesCapability;)V", remap = false)
    private void avilixlogger$init(@Coerce Object cap, Operation<Void> original) {
        var action = AccessoriesAudit.holder(this, cap, "holder-initialize"); boolean success = false;
        try { original.call(cap); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "read(Lio/wispforest/accessories/api/AccessoriesCapability;Lnet/minecraft/world/entity/LivingEntity;Lio/wispforest/endec/util/MapCarrier;Lio/wispforest/endec/SerializationContext;)V", remap = false)
    private void avilixlogger$read(@Coerce Object cap, LivingEntity entity, @Coerce Object carrier, @Coerce Object context, Operation<Void> original) {
        var action = AccessoriesAudit.holder(this, cap, "holder-read"); boolean success = false;
        try { original.call(cap, entity, carrier, context); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
}

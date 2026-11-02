package com.roften.avilixlogger.compat.accessories.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.accessories.AccessoriesAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.resources.ResourceLocation;

@Pseudo
@Mixin(targets = "io.wispforest.accessories.impl.AccessoriesContainerImpl", remap = false)
public abstract class ContainerMixin {
    @WrapMethod(method = "update()V", remap = false)
    private void avilixlogger$update(Operation<Void> original) {
        var action = AccessoriesAudit.updating(this) ? AccessoriesAudit.container(this, "resize") : null; boolean success = false;
        try { original.call(); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "addTransientModifier(Lnet/minecraft/world/entity/ai/attributes/AttributeModifier;)V", remap = false)
    private void avilixlogger$transientModifier(AttributeModifier modifier, Operation<Void> original) {
        var action = AccessoriesAudit.container(this, "container-transientModifier"); boolean success = false;
        try { original.call(modifier); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "addPersistentModifier(Lnet/minecraft/world/entity/ai/attributes/AttributeModifier;)V", remap = false)
    private void avilixlogger$persistentModifier(AttributeModifier modifier, Operation<Void> original) {
        var action = AccessoriesAudit.container(this, "container-persistentModifier"); boolean success = false;
        try { original.call(modifier); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "removeModifier(Lnet/minecraft/resources/ResourceLocation;)V", remap = false)
    private void avilixlogger$removeModifier(ResourceLocation id, Operation<Void> original) {
        var action = AccessoriesAudit.container(this, "container-removeModifier"); boolean success = false;
        try { original.call(id); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "clearModifiers()V", remap = false)
    private void avilixlogger$clearModifiers(Operation<Void> original) {
        var action = AccessoriesAudit.container(this, "container-clearModifiers"); boolean success = false;
        try { original.call(); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "clearCachedModifiers()V", remap = false)
    private void avilixlogger$clearCached(Operation<Void> original) {
        var action = AccessoriesAudit.container(this, "container-clearCached"); boolean success = false;
        try { original.call(); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "removeCachedModifiers(Lnet/minecraft/world/entity/ai/attributes/AttributeModifier;)V", remap = false)
    private void avilixlogger$removeCached(AttributeModifier modifier, Operation<Void> original) {
        var action = AccessoriesAudit.container(this, "container-removeCached"); boolean success = false;
        try { original.call(modifier); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "copyFrom(Lio/wispforest/accessories/impl/AccessoriesContainerImpl;)V", remap = false)
    private void avilixlogger$copy(@Coerce Object other, Operation<Void> original) {
        var action = AccessoriesAudit.container(this, "container-copy"); boolean success = false;
        try { original.call(other); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "read(Lio/wispforest/endec/util/MapCarrier;Lio/wispforest/endec/SerializationContext;Z)V", remap = false)
    private void avilixlogger$read(@Coerce Object carrier, @Coerce Object context, boolean sync, Operation<Void> original) {
        var action = AccessoriesAudit.container(this, "container-read"); boolean success = false;
        try { original.call(carrier, context, sync); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
}

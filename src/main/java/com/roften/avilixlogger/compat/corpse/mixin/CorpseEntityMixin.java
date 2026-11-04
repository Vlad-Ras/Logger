package com.roften.avilixlogger.compat.corpse.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.corpse.CorpseLifecycleAudit;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

@Pseudo
@Mixin(targets = "de.maxhenkel.corpse.entities.CorpseEntity", remap = false)
public abstract class CorpseEntityMixin implements com.roften.avilixlogger.compat.corpse.CorpseLifecycleAccess {
    @Shadow public abstract boolean isSkeleton();
    @WrapMethod(method = "remove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", remap = false)
    private void avilixlogger$remove(Entity.RemovalReason reason, Operation<Void> original) {
        var action = reason == Entity.RemovalReason.KILLED || reason == Entity.RemovalReason.DISCARDED
                ? CorpseLifecycleAudit.begin((Entity)(Object)this, "remove:" + reason.name(), true) : null;
        boolean success = false;
        try { original.call(reason); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setIsSkeleton(Z)V", remap = false)
    private void avilixlogger$skeleton(boolean skeleton, Operation<Void> original) {
        var action = skeleton == isSkeleton() ? null : CorpseLifecycleAudit.begin((Entity)(Object)this, "skeleton", false);
        boolean success = false;
        try { original.call(skeleton); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z", remap = false)
    private boolean avilixlogger$hurt(DamageSource damage, float amount, Operation<Boolean> original) {
        try (var reason = CorpseLifecycleAudit.reason((Entity)(Object)this, "damage", damage)) { return original.call(damage, amount); }
    }
    @WrapOperation(method = "tick()V", at = @At(value = "INVOKE", target = "Lde/maxhenkel/corpse/entities/CorpseEntity;discard()V", ordinal = 0), remap = false)
    private void avilixlogger$forcedTimeout(@Coerce Object body, Operation<Void> original) {
        try (var reason = CorpseLifecycleAudit.reason((Entity)body, "forced-timeout", null)) { original.call(body); }
    }
    @WrapOperation(method = "tick()V", at = @At(value = "INVOKE", target = "Lde/maxhenkel/corpse/entities/CorpseEntity;discard()V", ordinal = 1), remap = false)
    private void avilixlogger$emptyTimeout(@Coerce Object body, Operation<Void> original) {
        try (var reason = CorpseLifecycleAudit.reason((Entity)body, "empty-timeout", null)) { original.call(body); }
    }
    @WrapMethod(method = "readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", remap = false)
    private void avilixlogger$read(CompoundTag tag, Operation<Void> original) {
        var action = CorpseLifecycleAudit.begin((Entity)(Object)this, "state-read", false); boolean success = false;
        try { original.call(tag); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setCorpseUUID(Ljava/util/UUID;)V", remap = false)
    private void avilixlogger$owner(UUID id, Operation<Void> original) {
        var action = CorpseLifecycleAudit.begin((Entity)(Object)this, "state-owner", false); boolean success = false;
        try { original.call(id); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setCorpseName(Ljava/lang/String;)V", remap = false)
    private void avilixlogger$name(String name, Operation<Void> original) {
        var action = CorpseLifecycleAudit.begin((Entity)(Object)this, "state-name", false); boolean success = false;
        try { original.call(name); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setCorpseModel(B)V", remap = false)
    private void avilixlogger$model(byte model, Operation<Void> original) {
        var action = CorpseLifecycleAudit.begin((Entity)(Object)this, "state-model", false); boolean success = false;
        try { original.call(model); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setEquipment(Lnet/minecraft/core/NonNullList;)V", remap = false)
    private void avilixlogger$equipment(NonNullList<ItemStack> items, Operation<Void> original) {
        var action = CorpseLifecycleAudit.begin((Entity)(Object)this, "state-equipment", false); boolean success = false;
        try { original.call(items); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
}

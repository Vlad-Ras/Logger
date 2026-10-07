package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractContraptionEntity.class, remap = false)
public abstract class CartEntityAuditMixin implements CartEntityAccess {
    @org.spongepowered.asm.mixin.Shadow private boolean skipActorStop;
    @Override public void avilixlogger$discardForRollback() { skipActorStop=true;((Entity)(Object)this).discard(); }
    @Override public CartAuditState avilixlogger$mountedState() {
        var c = ((AbstractContraptionEntity)(Object)this).getContraption();
        return c instanceof CartAuditAccess a ? a.avilixlogger$cartState() : null;
    }
    @WrapMethod(method = "tick")
    private void avilixlogger$tick(Operation<Void> original) {
        Entity self = (Entity)(Object)this;
        CartAuditState s = avilixlogger$mountedState();
        if (s == null || self.level().isClientSide) { original.call(); return; }
        if (s.locked) return;
        if (!CreateCartAudit.enabled()) { original.call(); return; }
        CreateCartAudit.beforeTick((OrientedContraptionEntity) self);
        var previous = CartAuditContext.enter(s.stamp("work"));
        try { original.call(); CreateCartAudit.afterTick((OrientedContraptionEntity) self); }
        finally { CartAuditContext.restore(previous); }
    }
    @WrapMethod(method = "disassemble")
    private void avilixlogger$disassemble(Operation<Void> original) {
        Entity self = (Entity)(Object)this; CartAuditState s = avilixlogger$mountedState();
        if (s == null || self.level().isClientSide || !self.isAlive() || !CreateCartAudit.enabled()) { original.call(); return; }
        if (s.locked) return;
        var e = (OrientedContraptionEntity) self;
        s.sequence++;
        var before = CreateCartAudit.snapshot(e, null);
        var baseCart=e.getVehicle();
        var previous = CartAuditContext.enter(s.stamp("disassemble"));
        s.removing = true;
        try {
            original.call();
            if (e.isRemoved()) {
                var after=CreateCartAudit.form(e,"blocks");
                if(baseCart instanceof net.minecraft.world.entity.vehicle.AbstractMinecart cart)after.put("Cart",CreateCartAudit.baseSnapshot(cart));
                CreateCartAudit.emit(e, ActionType.CART_DISASSEMBLE, "disassemble", before, after, "disassembled");
            }
        } finally { s.removing = false; CartAuditContext.restore(previous); }
    }
    @Inject(method = "remove", at = @At("HEAD"), require = 1)
    private void avilixlogger$remove(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        if (avilixlogger$mountedState() != null && !self.level().isClientSide && !self.isRemoved() && CreateCartAudit.enabled()) {
            try { CreateCartAudit.removed((OrientedContraptionEntity) self, reason); }
            catch (Throwable error) { CreateCartAudit.failed("remove", error); }
        }
    }
    @WrapMethod(method = "setBlock")
    private void avilixlogger$structure(BlockPos pos, StructureBlockInfo after, Operation<Void> original) {
        var self = (AbstractContraptionEntity)(Object)this;
        var s = avilixlogger$mountedState();
        if (s == null || self.level().isClientSide || !CreateCartAudit.enabled()) { original.call(pos, after); return; }
        var before = self.getContraption().getBlocks().get(pos);
        original.call(pos, after);
        if (java.util.Objects.equals(before, after)) return;
        net.minecraft.nbt.CompoundTag b = new net.minecraft.nbt.CompoundTag(), a = new net.minecraft.nbt.CompoundTag();
        b.putLong("LocalPos", pos.asLong()); a.putLong("LocalPos", pos.asLong());
        if (before != null) { b.put("State", net.minecraft.nbt.NbtUtils.writeBlockState(before.state())); if (before.nbt() != null) b.put("Data", before.nbt().copy()); }
        a.put("State", net.minecraft.nbt.NbtUtils.writeBlockState(after.state())); if (after.nbt() != null) a.put("Data", after.nbt().copy());
        CreateCartAudit.emit((OrientedContraptionEntity)self, ActionType.CART_STRUCTURE_CHANGE, "structure", b, a, "local=" + pos.toShortString());
    }
}

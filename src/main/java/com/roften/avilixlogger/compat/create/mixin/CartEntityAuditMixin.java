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
        if(!self.level().isClientSide&&CreateCargoAudit.restoreLocked((AbstractContraptionEntity)self))return;
        if (s == null || self.level().isClientSide) { original.call(); return; }
        if (s.locked) return;
        if (!CreateCapturePolicy.allows((AbstractContraptionEntity)self) || !CreateCartAudit.tickEnabled()) { original.call(); return; }
        CreateCartAudit.beforeTick((OrientedContraptionEntity) self);
        var previous = CartAuditContext.enter(s.stamp("work"));
        try { original.call(); CreateCartAudit.afterTick((OrientedContraptionEntity) self); }
        finally { CartAuditContext.restore(previous); }
    }
    @WrapMethod(method = "disassemble")
    private void avilixlogger$disassemble(Operation<Void> original) {
        Entity self = (Entity)(Object)this; CartAuditState s = avilixlogger$mountedState();
        if(CreateCargoAudit.restoreLocked((AbstractContraptionEntity)self))return;
        if(CreateStructuralAudit.covered((AbstractContraptionEntity)self)){original.call();return;}
        if(s==null){try(var scope=CreateLifecycleAudit.disassembly((AbstractContraptionEntity)self)){original.call();if(scope!=null)scope.success();}return;}
        if (self.level().isClientSide || !self.isAlive() || !CreateCartAudit.enabled()) { original.call(); return; }
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
        if (CartAuditContext.rollbackActive) {CartRollbackCoordinator.invalidated(self);CreateTransferRollbackCoordinator.invalidated(self);}
        if(avilixlogger$mountedState()==null)try{CreateLifecycleAudit.removed((AbstractContraptionEntity)self,reason);}catch(RuntimeException failure){CreateCartAudit.failed("typed lifecycle remove",failure);}
        if (avilixlogger$mountedState() != null && !self.level().isClientSide && !self.isRemoved() && CreateCartAudit.enabled()) {
            try { CreateCartAudit.removed((OrientedContraptionEntity) self, reason); }
            catch (Throwable error) { CreateCartAudit.failed("remove", error); }
        }
    }
    @WrapMethod(method = "handlePlayerInteraction")
    private boolean avilixlogger$interact(net.minecraft.world.entity.player.Player player, BlockPos pos, net.minecraft.core.Direction side, net.minecraft.world.InteractionHand hand, Operation<Boolean> original) {
        var self=(AbstractContraptionEntity)(Object)this;var state=avilixlogger$mountedState();
        if(CreateCargoAudit.restoreLocked(self))return false;
        if(!CreateCargoAudit.active(self))return original.call(player,pos,side,hand);
        if(state!=null&&state.locked||CartRestoreLocks.playerLocked(player))return false;
        var previousSource=MutationSourceResolver.enter(CreateContraptionSources.kind(self).source(CreateContraptionSources.id(self),"interaction"));
        try(var cause=CauseContext.push(player,CauseContext.Kind.INTERACT_ENTITY,self.blockPosition(),player.getItemInHand(hand));
            var structure=CreateStructureAudit.interaction(self,pos)) {
            var depot=CartMenuTransferAudit.beginDepot(player,self,pos,hand);boolean success=false;boolean result;
            try{result=original.call(player,pos,side,hand);success=true;}finally{if(depot!=null)depot.finish(success);}
            if(result&&state!=null&&CreateCartAudit.enabled()){
                net.minecraft.nbt.CompoundTag detail=new net.minecraft.nbt.CompoundTag();detail.putLong("LocalPos",pos.asLong());
                net.minecraft.nbt.ListTag disabled=new net.minecraft.nbt.ListTag();
                for(var filter:self.getContraption().getDisabledActors())disabled.add(filter.saveOptional(self.registryAccess()));
                detail.put("DisabledActors",disabled);
                CreateCartAudit.emit((OrientedContraptionEntity)self,ActionType.CART_STATUS,"interaction",null,detail,"interaction; local="+pos.toShortString()+"; side="+side);
            }
            return result;
        }finally{MutationSourceResolver.restore(previousSource);}
    }
    @WrapMethod(method = "setBlock")
    private void avilixlogger$structure(BlockPos pos, StructureBlockInfo after, Operation<Void> original) {
        var self = (AbstractContraptionEntity)(Object)this;
        if(CreateCargoAudit.restoreLocked(self))return;
        if (!CreateCargoAudit.active(self)) { original.call(pos, after); return; }
        var before = CreateStructureAudit.beforeChange(self,pos);
        original.call(pos, after);
        CreateStructureAudit.changed(self,pos,before);
    }
}

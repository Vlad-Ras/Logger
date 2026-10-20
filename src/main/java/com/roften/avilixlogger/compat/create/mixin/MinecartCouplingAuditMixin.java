package com.roften.avilixlogger.compat.create.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CreateCouplingAudit;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.minecart.capability.MinecartController;
import org.spongepowered.asm.mixin.Mixin;
import java.util.UUID;
@Mixin(value=MinecartController.class,remap=false)
public abstract class MinecartCouplingAuditMixin {
    @org.spongepowered.asm.mixin.injection.Inject(method="deserializeNBT",at=@org.spongepowered.asm.mixin.injection.At("HEAD"),cancellable=true)
    private void avilixlogger$deserialize(net.minecraft.core.HolderLookup.Provider provider,net.minecraft.nbt.CompoundTag data,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){if(CreateCouplingAudit.lockedChain((MinecartController)(Object)this))ci.cancel();}
    @org.spongepowered.asm.mixin.Unique
    private boolean avilixlogger$locked(){return CreateCouplingAudit.lockedChain((MinecartController)(Object)this);}
    @org.spongepowered.asm.mixin.injection.Inject(method="tick",at=@org.spongepowered.asm.mixin.injection.At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$tick(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){if(CreateCouplingAudit.locked(((MinecartController)(Object)this).cart()))ci.cancel();}
    @WrapMethod(method="coupleWith")
    private void avilixlogger$connect(boolean leading,UUID other,float length,boolean contraption,Operation<Void> original){
        if(avilixlogger$locked())return;
        if(CartAuditContext.rollbackActive&&!CartAuditContext.restoring()&&CartAuditContext.LOCKED_ENTITIES.contains(other))return;
        try(var scope=CreateCouplingAudit.controller((MinecartController)(Object)this,false)){original.call(leading,other,length,contraption);if(scope!=null)scope.success();}
    }
    @WrapMethod(method="removeConnection")
    private void avilixlogger$disconnect(boolean leading,Operation<Void> original){
        if(avilixlogger$locked())return;
        try(var scope=CreateCouplingAudit.controller((MinecartController)(Object)this,false)){original.call(leading);if(scope!=null)scope.success();}
    }
    @WrapMethod(method="prepareForCoupling")
    private void avilixlogger$reverse(boolean leading,Operation<Void> original){
        if(avilixlogger$locked())return;
        try(var scope=CreateCouplingAudit.controller((MinecartController)(Object)this,true)){original.call(leading);if(scope!=null)scope.success();}
    }
    @WrapMethod(method="decouple")
    private void avilixlogger$decouple(Operation<Void> original){
        if(avilixlogger$locked())return;
        try(var structure=com.roften.avilixlogger.compat.create.CreateStructuralAudit.beginCoupled((MinecartController)(Object)this);var scope=CreateCouplingAudit.controller((MinecartController)(Object)this,false)){original.call();if(scope!=null)scope.success();if(structure!=null)structure.success();}
    }
}

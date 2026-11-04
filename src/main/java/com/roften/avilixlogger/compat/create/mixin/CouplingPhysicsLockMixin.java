package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateCouplingAudit;
import com.simibubi.create.content.contraptions.minecart.CouplingPhysics;
import com.simibubi.create.content.contraptions.minecart.capability.MinecartController;
import net.createmod.catnip.data.Couple;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Coupling physics bypasses the reserved cart's entity tick. */
@Mixin(value=CouplingPhysics.class,remap=false)
public abstract class CouplingPhysicsLockMixin {
    @Inject(method="tickCoupling",at=@At("HEAD"),cancellable=true,require=1)
    private static void avilixlogger$locked(Level level,Couple<MinecartController> pair,CallbackInfo ci){
        if(!level.isClientSide&&(CreateCouplingAudit.locked(pair.getFirst().cart())||CreateCouplingAudit.locked(pair.getSecond().cart())))ci.cancel();
    }
}

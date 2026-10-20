package com.roften.avilixlogger.compat.create.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.CartAuditContext;
import com.simibubi.create.content.logistics.box.PackageEntity;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value=PackageEntity.class,remap=false)
public abstract class PackageRestoreLockMixin {
    @WrapMethod(method="interact")
    private InteractionResult avilixlogger$interact(Player player,InteractionHand hand,Operation<InteractionResult> original){
        if(locked())return InteractionResult.PASS;return original.call(player,hand);
    }
    @WrapMethod(method="hurt")
    private boolean avilixlogger$hurt(DamageSource source,float damage,Operation<Boolean> original){
        if(locked())return false;return original.call(source,damage);
    }
    @org.spongepowered.asm.mixin.Unique
    private boolean locked(){return CartAuditContext.rollbackActive&&!CartAuditContext.restoring()&&CartAuditContext.LOCKED_ENTITIES.contains(((PackageEntity)(Object)this).getUUID());}
}

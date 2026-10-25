package com.roften.avilixlogger.compat.scguns.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import com.roften.avilixlogger.compat.scguns.ScgunsTurretAudit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import java.util.UUID;

@Pseudo
@Mixin(targets="top.ribs.scguns.blockentity.TurretBlockEntity",remap=false)
public abstract class TurretFireMixin {
    @Shadow protected UUID ownerUUID;
    @Shadow protected String ownerName;
    @WrapMethod(method="fire(Ltop/ribs/scguns/common/Turret$Ammunition$AmmoType;I)V",remap=false)
    private void avilixlogger$fire(@Coerce Object ammo,int bonus,Operation<Void> original){
        var scope=ScgunsTurretAudit.begin((BlockEntity)(Object)this,ownerUUID,ownerName);
        try {original.call(ammo,bonus);}finally {if(scope!=null)scope.close();}
    }
    @WrapOperation(method={"fireSingleProjectile","fireCluster"},at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"),remap=false)
    private boolean avilixlogger$spawn(Level level,Entity entity,Operation<Boolean> original,
            @Local(argsOnly=true,ordinal=0) Vec3 muzzle,@Local(argsOnly=true,ordinal=1) Vec3 aim){
        var shot=ScgunsTurretAudit.prepare(entity,muzzle,aim);
        boolean accepted=original.call(level,entity);ScgunsTurretAudit.spawned(entity,accepted,shot);return accepted;
    }
}

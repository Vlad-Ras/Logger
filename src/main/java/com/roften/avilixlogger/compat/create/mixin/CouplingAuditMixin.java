package com.roften.avilixlogger.compat.create.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.minecart.CouplingHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value=CouplingHandler.class,remap=false)
public abstract class CouplingAuditMixin {
    @WrapMethod(method="tryToCoupleCarts")
    private static boolean avilixlogger$couple(Player player,Level world,int first,int second,Operation<Boolean> original){
        if(world.isClientSide)return original.call(player,world,first,second);
        if(CartAuditContext.rollbackActive&&!CartAuditContext.restoring())for(int id:new int[]{first,second}){
            if(world.getEntity(id) instanceof net.minecraft.world.entity.vehicle.AbstractMinecart cart
                    &&(CreateCouplingAudit.locked(cart)||CreateCouplingAudit.lockedChain(com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController.getIfPresent(world,cart.getUUID()))))return false;
        }
        if(!CreateCartAudit.enabled())return original.call(player,world,first,second);
        try(var cause=player==null?null:CauseContext.push(player,CauseContext.Kind.OTHER,player.blockPosition(),player.getMainHandItem());var scope=CreateCouplingAudit.begin()){
            boolean result=original.call(player,world,first,second);if(scope!=null)scope.success();return result;
        }
    }
}

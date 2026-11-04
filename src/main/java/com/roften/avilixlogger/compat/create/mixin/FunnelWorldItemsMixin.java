package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.CartAuditContext;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.logistics.funnel.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.List;
import java.util.function.Predicate;

@Mixin(value=FunnelMovementBehaviour.class,remap=false)
public abstract class FunnelWorldItemsMixin {
    @WrapMethod(method="visitNewPosition")
    private void avilixlogger$funnel(MovementContext context,BlockPos pos,Operation<Void> original){
        boolean extracting=context.state.getValue(FunnelBlock.EXTRACTING);
        try(var effects=CreateActorEffectsAudit.begin(context,extracting?"funnel_extract":"funnel_suction",extracting,false)) {
            var transfer=extracting?null:CartAutomationAudit.mounted(context,pos);boolean success=false;
            try{original.call(context,pos);success=true;if(effects!=null)effects.success();}
            finally{if(transfer!=null)transfer.finish(success);}
        }
    }
    @WrapOperation(method="succ",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"),require=1)
    private List<Entity> avilixlogger$selected(Level world,Entity except,AABB box,Predicate<? super Entity> filter,Operation<List<Entity>> original){
        var entities=original.call(world,except,box,filter);CartAutomationAudit.worldItems(entities);return entities;
    }
    @WrapOperation(method="succ",at=@At(value="INVOKE",target="Lcom/simibubi/create/foundation/item/ItemHelper;fromItemEntity(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/item/ItemStack;"),require=1)
    private ItemStack avilixlogger$locked(Entity entity,Operation<ItemStack> original){
        if(CartAuditContext.rollbackActive&&!CartAuditContext.restoring()&&CartAuditContext.LOCKED_ENTITIES.contains(entity.getUUID()))return ItemStack.EMPTY;
        return original.call(entity);
    }
}

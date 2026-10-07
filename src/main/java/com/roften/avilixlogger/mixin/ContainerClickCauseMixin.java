package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.CauseContext;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ContainerClickCauseMixin {
    @Shadow public ServerPlayer player;
    @WrapMethod(method="handleContainerClick")
    private void avilixlogger$cause(ServerboundContainerClickPacket packet,Operation<Void> original){
        if(!player.server.isSameThread() || !LoggerConfig.isEnabled()){original.call(packet);return;}
        if(com.roften.avilixlogger.core.CartRestoreLocks.menuLocked(player.containerMenu)){player.containerMenu.broadcastFullState();return;}
        if(com.roften.avilixlogger.core.CartAuditContext.rollbackActive)for(var slot:player.containerMenu.slots) {
            if(slot.container instanceof net.minecraft.world.entity.Entity entity && com.roften.avilixlogger.core.CartAuditContext.LOCKED_ENTITIES.contains(entity.getUUID())){player.containerMenu.broadcastFullState();return;}
            if(slot.container instanceof net.minecraft.world.level.block.entity.BlockEntity be && com.roften.avilixlogger.core.CartRestoreLocks.blockLocked(be.getLevel(),be.getBlockPos())){player.containerMenu.broadcastFullState();return;}
        }
        try(var cause=CauseContext.push(player,CauseContext.Kind.OTHER,player.blockPosition(),ItemStack.EMPTY)){original.call(packet);}
    }
}

package com.roften.avilixlogger.compat.industry.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.*;
import com.roften.avilixlogger.compat.industry.*;

@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.networking.BlockEntityConfigurationPacket", remap = false)
public abstract class ConfigurationMixin {
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "handle(Lnet/minecraft/server/level/ServerPlayer;)V",
        at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/networking/BlockEntityConfigurationPacket;applySettings(Lnet/minecraft/server/level/ServerPlayer;Lcom/simibubi/create/foundation/blockEntity/SyncedBlockEntity;)V"), remap = false)
    private void avilixlogger$settings(@Coerce Object packet, ServerPlayer player, @Coerce Object be, Operation<Void> original) {
        if(player==null||!player.server.isSameThread()){original.call(packet,player,be);return;}
        try(var cause=com.roften.avilixlogger.core.CauseContext.push(player,com.roften.avilixlogger.core.CauseContext.Kind.OTHER,
                ((net.minecraft.world.level.block.entity.BlockEntity)be).getBlockPos(),ItemStack.EMPTY)){
            var action=IndustrialAudit.begin(be,"player-settings",false);boolean success=false;
            try{original.call(packet,player,be);success=true;}finally{if(action!=null)action.finish(!success);}
        }
    }
}

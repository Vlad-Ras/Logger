package com.roften.avilixlogger.compat.dieselgenerators.mixin;
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
@Mixin(targets = "com.jesz.createdieselgenerators.packets.EntityFilterScreenPacket", remap = false)
public abstract class FilterPacketMixin {
    @WrapMethod(method = "handle(Lnet/minecraft/server/level/ServerPlayer;)V", remap = false)
    private void avilixlogger$filter(ServerPlayer player, Operation<Void> original){
        if(player==null||!player.server.isSameThread()){original.call(player);return;}
        try(var cause=com.roften.avilixlogger.core.CauseContext.push(player,com.roften.avilixlogger.core.CauseContext.Kind.OTHER,player.blockPosition(),ItemStack.EMPTY)){
            var action=DieselAudit.filter(player);boolean success=false;
            try{original.call(player);success=true;}finally{if(action!=null)action.finish(!success);}
        }
    }
}

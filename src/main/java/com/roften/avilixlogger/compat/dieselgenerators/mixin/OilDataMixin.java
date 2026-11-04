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
@Mixin(targets = "com.jesz.createdieselgenerators.world.OilChunksSavedData", remap = false)
public abstract class OilDataMixin {
    @WrapMethod(method = "setChunkAmount(Lnet/minecraft/world/level/ChunkPos;I)V", remap = false)
    private void avilixlogger$set(net.minecraft.world.level.ChunkPos pos, int amount, Operation<Void> original){
        var action=DieselAudit.oil(this,pos); boolean success=false;
        try{original.call(pos, amount);success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "removeChunk(Lnet/minecraft/world/level/ChunkPos;)V", remap = false)
    private void avilixlogger$remove(net.minecraft.world.level.ChunkPos pos, Operation<Void> original){
        var action=DieselAudit.oil(this,pos); boolean success=false;
        try{original.call(pos);success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
}

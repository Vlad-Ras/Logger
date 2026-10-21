package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateHumanRuntimeAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import com.mojang.authlib.GameProfile;
import org.spongepowered.asm.mixin.Mixin;
/** Nonvirtual Player load: never reload a connected player's dimension or server session. */
@Mixin(ServerPlayer.class)
public abstract class HumanRuntimeMixin extends Player implements CreateHumanRuntimeAccess {
    protected HumanRuntimeMixin(Level level,BlockPos pos,float yaw,GameProfile profile){super(level,pos,yaw,profile);}
    public void avilixlogger$loadPlayerRuntime(CompoundTag tag){super.readAdditionalSaveData(tag);}
}

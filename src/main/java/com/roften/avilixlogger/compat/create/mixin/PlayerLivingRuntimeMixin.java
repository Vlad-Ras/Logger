package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreatePlayerLivingAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
/** Do not reload a ServerPlayer's world/session when restoring food or effects. */
@Mixin(Player.class)
public abstract class PlayerLivingRuntimeMixin extends LivingEntity implements CreatePlayerLivingAccess {
    protected PlayerLivingRuntimeMixin(EntityType<? extends LivingEntity> type,Level level){super(type,level);}
    public CompoundTag avilixlogger$baseLiving(){var tag=new CompoundTag();super.addAdditionalSaveData(tag);return tag;}
    public void avilixlogger$baseLiving(CompoundTag tag){super.readAdditionalSaveData(tag);}
}

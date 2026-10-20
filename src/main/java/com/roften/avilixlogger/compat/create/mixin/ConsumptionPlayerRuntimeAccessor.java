package com.roften.avilixlogger.compat.create.mixin;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ServerPlayer.class)
public interface ConsumptionPlayerRuntimeAccessor {
    @Accessor("levitationStartTime") int avilixlogger$levitationTime();
    @Accessor("levitationStartTime") void avilixlogger$levitationTime(int value);
    @Accessor("levitationStartPos") Vec3 avilixlogger$levitationPos();
    @Accessor("levitationStartPos") void avilixlogger$levitationPos(Vec3 value);
}

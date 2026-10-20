package com.roften.avilixlogger.compat.create.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.compat.create.CreateTrainsCompatHooks;
import com.simibubi.create.content.trains.schedule.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(value=ScheduleItem.class,remap=false)
public abstract class ScheduleItemMixin {
    @WrapOperation(method="handScheduleTo",at=@At(value="INVOKE",target="Lcom/simibubi/create/content/trains/schedule/ScheduleRuntime;setSchedule(Lcom/simibubi/create/content/trains/schedule/Schedule;Z)V"),require=1)
    private void avilixlogger$put(ScheduleRuntime runtime,Schedule schedule,boolean auto,Operation<Void> original,ItemStack stack,Player player,net.minecraft.world.entity.LivingEntity target,InteractionHand hand){
        ItemStack before=stack.copyWithCount(1);original.call(runtime,schedule,auto);
        if(player instanceof ServerPlayer && player.level() instanceof ServerLevel level)
            CreateTrainsCompatHooks.logSchedulePut(level,runtime.train,CreateTrainsCompatHooks.actorUuid(player),CreateTrainsCompatHooks.actorName(player),player.blockPosition(),before);
    }
}

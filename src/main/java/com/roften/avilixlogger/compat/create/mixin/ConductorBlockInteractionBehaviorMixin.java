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
import com.simibubi.create.api.behaviour.interaction.ConductorBlockInteractionBehavior;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
@Mixin(value=ConductorBlockInteractionBehavior.class,remap=false)
public abstract class ConductorBlockInteractionBehaviorMixin {
    @WrapOperation(method="handlePlayerInteraction",at=@At(value="INVOKE",target="Lcom/simibubi/create/content/trains/schedule/ScheduleRuntime;setSchedule(Lcom/simibubi/create/content/trains/schedule/Schedule;Z)V"),require=1)
    private void avilixlogger$put(ScheduleRuntime runtime,Schedule schedule,boolean auto,Operation<Void> original,Player player,InteractionHand hand,BlockPos local,AbstractContraptionEntity entity){
        ItemStack before=player.getItemInHand(hand).copyWithCount(1);original.call(runtime,schedule,auto);
        if(player instanceof ServerPlayer && player.level() instanceof ServerLevel level)
            CreateTrainsCompatHooks.logSchedulePut(level,runtime.train,CreateTrainsCompatHooks.actorUuid(player),CreateTrainsCompatHooks.actorName(player),player.blockPosition(),before);
    }
    @WrapOperation(method="handlePlayerInteraction",at=@At(value="INVOKE",target="Lcom/simibubi/create/content/trains/schedule/ScheduleRuntime;returnSchedule(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;"),require=1)
    private ItemStack avilixlogger$take(ScheduleRuntime runtime,HolderLookup.Provider registries,Operation<ItemStack> original,Player player,InteractionHand hand,BlockPos local,AbstractContraptionEntity entity){
        ItemStack returned=original.call(runtime,registries);
        if(player instanceof ServerPlayer && player.level() instanceof ServerLevel level)
            CreateTrainsCompatHooks.logScheduleTaken(level,runtime.train,CreateTrainsCompatHooks.actorUuid(player),CreateTrainsCompatHooks.actorName(player),player.blockPosition(),returned);
        return returned;
    }
}

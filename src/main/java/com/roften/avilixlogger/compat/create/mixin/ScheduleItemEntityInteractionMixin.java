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
@Mixin(value=ScheduleItemEntityInteraction.class,remap=false)
public abstract class ScheduleItemEntityInteractionMixin {
    @WrapOperation(method="interactWithConductor",at=@At(value="INVOKE",target="Lcom/simibubi/create/content/trains/schedule/ScheduleRuntime;returnSchedule(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;"),require=1)
    private static ItemStack avilixlogger$take(ScheduleRuntime runtime,HolderLookup.Provider registries,Operation<ItemStack> original,net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific event){
        ItemStack returned=original.call(runtime,registries);var player=event.getEntity();
        if(player instanceof ServerPlayer && player.level() instanceof ServerLevel level)
            CreateTrainsCompatHooks.logScheduleTaken(level,runtime.train,CreateTrainsCompatHooks.actorUuid(player),CreateTrainsCompatHooks.actorName(player),player.blockPosition(),returned);
        return returned;
    }
}

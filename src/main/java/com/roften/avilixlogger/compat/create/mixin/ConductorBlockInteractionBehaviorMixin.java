package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.compat.create.CreateTrainsCompatHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mixin(targets = "com.simibubi.create.api.behaviour.interaction.ConductorBlockInteractionBehavior")
public class ConductorBlockInteractionBehaviorMixin {

    // === ЛОГ: поставил расписание (setSchedule) ===
    @Redirect(
            method = "handlePlayerInteraction",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/schedule/ScheduleRuntime;setSchedule(Lcom/simibubi/create/content/trains/schedule/Schedule;Z)V"
            ),
            require = 0
    )
    private void avilixlogger$setScheduleAndLog(
            @Coerce Object runtime,
            @Coerce Object schedule,
            boolean auto,
            Player player,
            InteractionHand activeHand,
            BlockPos localPos,
            @Coerce Object contraptionEntity
    ) {
        // снимем снапшот предмета ДО shrink()
        ItemStack scheduleItem = ItemStack.EMPTY;
        try {
            scheduleItem = player.getItemInHand(activeHand);
            if (!scheduleItem.isEmpty())
                scheduleItem = scheduleItem.copyWithCount(1);
        } catch (Throwable ignored) {}

        invokeSetSchedule(runtime, schedule, auto);

        try {
            if (!(player instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel sl)) return;

            Object train = readField(runtime, "train");
            if (train == null) return;

            CreateTrainsCompatHooks.logSchedulePut(
                    sl,
                    train,
                    sp.getUUID(),
                    sp.getGameProfile().getName(),
                    sp.blockPosition(),
                    scheduleItem
            );
        } catch (Throwable ignored) {}
    }

    // === ЛОГ: забрал расписание (returnSchedule) ===
    @Redirect(
            method = "handlePlayerInteraction",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/schedule/ScheduleRuntime;returnSchedule(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;"
            ),
            require = 0
    )
    private ItemStack avilixlogger$returnScheduleAndLog(
            @Coerce Object runtime,
            HolderLookup.Provider registries,
            Player player,
            InteractionHand activeHand,
            BlockPos localPos,
            @Coerce Object contraptionEntity
    ) {
        ItemStack returned = invokeReturnSchedule(runtime, registries);

        try {
            if (!(player instanceof ServerPlayer sp)) return returned;
            if (!(sp.level() instanceof ServerLevel sl)) return returned;

            Object train = readField(runtime, "train");
            if (train == null) return returned;

            CreateTrainsCompatHooks.logScheduleTaken(
                    sl,
                    train,
                    sp.getUUID(),
                    sp.getGameProfile().getName(),
                    sp.blockPosition(),
                    returned
            );
        } catch (Throwable ignored) {}

        return returned;
    }

    // ===== helpers =====

    private static void invokeSetSchedule(Object runtime, Object schedule, boolean auto) {
        if (runtime == null) return;
        try {
            for (Method m : runtime.getClass().getMethods()) {
                if (!m.getName().equals("setSchedule")) continue;
                if (m.getParameterCount() != 2) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p[1] != boolean.class) continue;
                m.invoke(runtime, schedule, auto);
                return;
            }
        } catch (Throwable ignored) {}
    }

    private static ItemStack invokeReturnSchedule(Object runtime, HolderLookup.Provider registries) {
        if (runtime == null) return ItemStack.EMPTY;
        try {
            for (Method m : runtime.getClass().getMethods()) {
                if (!m.getName().equals("returnSchedule")) continue;
                if (m.getParameterCount() != 1) continue;
                Object r = m.invoke(runtime, registries);
                return (r instanceof ItemStack is) ? is : ItemStack.EMPTY;
            }
        } catch (Throwable ignored) {}
        return ItemStack.EMPTY;
    }

    private static Object readField(Object obj, String field) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ex) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
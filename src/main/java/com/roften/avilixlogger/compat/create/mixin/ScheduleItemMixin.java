package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.compat.create.CreateTrainsCompatHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mixin(targets = "com.simibubi.create.content.trains.schedule.ScheduleItem")
public class ScheduleItemMixin {

    @Redirect(
            method = "handScheduleTo",
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
            ItemStack pStack,
            Player pPlayer,
            LivingEntity pInteractionTarget,
            InteractionHand pUsedHand
    ) {
        // логируем предмет расписания ДО shrink()
        ItemStack scheduleItem = ItemStack.EMPTY;
        try {
            if (pStack != null && !pStack.isEmpty())
                scheduleItem = pStack.copyWithCount(1);
        } catch (Throwable ignored) {}

        invokeSetSchedule(runtime, schedule, auto);

        try {
            if (!(pPlayer instanceof ServerPlayer sp)) return;
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
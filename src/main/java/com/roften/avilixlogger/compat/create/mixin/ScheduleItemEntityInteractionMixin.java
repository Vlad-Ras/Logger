package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.compat.create.CreateTrainsCompatHooks;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mixin(targets = "com.simibubi.create.content.trains.schedule.ScheduleItemEntityInteraction")
public class ScheduleItemEntityInteractionMixin {

    @Redirect(
            method = "interactWithConductor",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/schedule/ScheduleRuntime;returnSchedule(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;"
            ),
            require = 0
    )
    private static ItemStack avilixlogger$returnScheduleAndLog(
            @Coerce Object runtime,
            HolderLookup.Provider registries,
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific event
    ) {
        ItemStack returned = invokeReturnSchedule(runtime, registries);

        try {
            if (!(event.getEntity() instanceof ServerPlayer sp)) return returned;
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
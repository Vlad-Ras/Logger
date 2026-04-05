package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.compat.create.CreateTrainsCompatHooks;
import com.roften.avilixlogger.core.CreateContraptionSnapshotStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

@Mixin(targets = "com.simibubi.create.content.contraptions.AbstractContraptionEntity")
public class AbstractContraptionEntityMixin {


    @Inject(method = "disassemble", at = @At("HEAD"), require = 0)
    private void avilixlogger$captureBeforeDisassemble(CallbackInfo ci) {
        try {
            Entity self = (Entity) (Object) this;
            if (self.level() instanceof ServerLevel sl) {
                CreateContraptionSnapshotStore.remember(sl, self, "disassemble");
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "remove", at = @At("HEAD"), require = 0)
    private void avilixlogger$captureBeforeRemove(RemovalReason reason, CallbackInfo ci) {
        try {
            Entity self = (Entity) (Object) this;
            if (self.level() instanceof ServerLevel sl) {
                String why = reason == null ? "remove" : ("remove:" + reason.name().toLowerCase(java.util.Locale.ROOT));
                CreateContraptionSnapshotStore.remember(sl, self, why);
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "kill", at = @At("HEAD"), require = 0)
    private void avilixlogger$captureBeforeKill(CallbackInfo ci) {
        try {
            Entity self = (Entity) (Object) this;
            if (self.level() instanceof ServerLevel sl) {
                CreateContraptionSnapshotStore.remember(sl, self, "kill");
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "stopControlling", at = @At("HEAD"), require = 0)
    private void avilixlogger$controlStop(BlockPos controlsLocalPos, CallbackInfo ci) {
        try {
            Entity self = (Entity) (Object) this;
            if (!(self.level() instanceof ServerLevel sl)) return;

            // интересуют только поездные contraption entity
            Class<?> carriageEntityClass = Class.forName("com.simibubi.create.content.trains.entity.CarriageContraptionEntity");
            if (!carriageEntityClass.isInstance(self)) return;

            UUID actorUuid = readControllingPlayerUuid(self);
            if (actorUuid == null) return;

            String actorName = actorUuid.toString();
            ServerPlayer sp = sl.getServer().getPlayerList().getPlayer(actorUuid);
            if (sp != null) actorName = sp.getGameProfile().getName();

            Object carriage = invoke0(self, "getCarriage");
            Object train = readField(carriage, "train");
            if (train == null) return;

            CreateTrainsCompatHooks.logControlStop(sl, train, actorUuid, actorName, self.blockPosition());
        } catch (Throwable ignored) {}
    }

    private static UUID readControllingPlayerUuid(Object instance) {
        try {
            // public Optional<UUID> getControllingPlayer()
            Object opt = invoke0(instance, "getControllingPlayer");
            if (opt instanceof Optional<?> o && o.isPresent() && o.get() instanceof UUID u) return u;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object invoke0(Object instance, String name) {
        if (instance == null) return null;
        try {
            for (Method m : instance.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() != 0) continue;
                return m.invoke(instance);
            }
        } catch (Throwable ignored) {}
        return null;
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
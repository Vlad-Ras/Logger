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

    @org.spongepowered.asm.mixin.Unique private Object avilixlogger$sourceContraption;
    @org.spongepowered.asm.mixin.Unique private UUID avilixlogger$sourceId;
    @org.spongepowered.asm.mixin.Unique private String avilixlogger$typedSource;

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = {"tickActors", "refreshPSIs"}, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/api/behaviour/movement/MovementBehaviour;visitNewPosition(Lcom/simibubi/create/content/contraptions/behaviour/MovementContext;Lnet/minecraft/core/BlockPos;)V"), require = 1, remap = false)
    private void avilixlogger$actorVisit(com.simibubi.create.api.behaviour.movement.MovementBehaviour actor,
            com.simibubi.create.content.contraptions.behaviour.MovementContext context, BlockPos pos,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        var previous = com.roften.avilixlogger.compat.create.CreateMechanismAudit.enter(context);
        try { original.call(actor, context, pos); }
        finally { com.roften.avilixlogger.core.CreateMechanismContext.restore(previous); }
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "tickActors", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/api/behaviour/movement/MovementBehaviour;tick(Lcom/simibubi/create/content/contraptions/behaviour/MovementContext;)V"), require = 1, remap = false)
    private void avilixlogger$actorTick(com.simibubi.create.api.behaviour.movement.MovementBehaviour actor,
            com.simibubi.create.content.contraptions.behaviour.MovementContext context,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        var previous = com.roften.avilixlogger.compat.create.CreateMechanismAudit.enter(context);
        try { original.call(actor, context); }
        finally { com.roften.avilixlogger.core.CreateMechanismContext.restore(previous); }
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "tickActors", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/api/behaviour/movement/MovementBehaviour;onSpeedChanged(Lcom/simibubi/create/content/contraptions/behaviour/MovementContext;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V"), require = 1, remap = false)
    private void avilixlogger$actorSpeed(com.simibubi.create.api.behaviour.movement.MovementBehaviour actor,
            com.simibubi.create.content.contraptions.behaviour.MovementContext context,
            net.minecraft.world.phys.Vec3 oldMotion, net.minecraft.world.phys.Vec3 motion,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        var previous = com.roften.avilixlogger.compat.create.CreateMechanismAudit.enter(context);
        try { original.call(actor, context, oldMotion, motion); }
        finally { com.roften.avilixlogger.core.CreateMechanismContext.restore(previous); }
    }

    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "tick")
    private void avilixlogger$typedTick(com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        var self = (com.simibubi.create.content.contraptions.AbstractContraptionEntity)(Object)this;
        // Skip per-tick provenance, ID and actor bookkeeping for excluded kinds.
        if (self.level().isClientSide || !com.roften.avilixlogger.compat.create.CreateCapturePolicy.allows(self)
                || !com.roften.avilixlogger.compat.create.CreateCartAudit.enabled()
                || self instanceof com.roften.avilixlogger.compat.create.CartEntityAccess access && access.avilixlogger$mountedState() != null) {
            original.call(); return;
        }
        var id = com.roften.avilixlogger.compat.create.CreateContraptionSources.id(self);
        if (avilixlogger$sourceContraption != self.getContraption() || !id.equals(avilixlogger$sourceId)) {
            avilixlogger$sourceContraption = self.getContraption();
            avilixlogger$sourceId = id;
            var kind = com.roften.avilixlogger.compat.create.CreateContraptionSources.kind(self);
            avilixlogger$typedSource = kind == com.roften.avilixlogger.core.CreateContraptionKind.UNKNOWN ? null : kind.source(id, "work");
        }
        if (avilixlogger$typedSource == null) { original.call(); return; }
        var previous = com.roften.avilixlogger.core.MutationSourceResolver.enter(avilixlogger$typedSource);
        try { original.call(); } finally { com.roften.avilixlogger.core.MutationSourceResolver.restore(previous); }
    }


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

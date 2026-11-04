package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.compat.create.CreateTrainsCompatHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mixin(targets = "com.simibubi.create.content.trains.entity.CarriageContraptionEntity")
public class CarriageContraptionEntityMixin {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$restoreLock(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){
        var self=(Entity)(Object)this;
        if(!self.level().isClientSide&&com.roften.avilixlogger.compat.create.CreateCargoAudit.restoreLocked((com.simibubi.create.content.trains.entity.CarriageContraptionEntity)self))ci.cancel();
    }


    // Carriages override setBlock without calling the base method.
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "setBlock")
    private void avilixlogger$structure(BlockPos pos, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo after,
                                       com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        var self = (com.simibubi.create.content.trains.entity.CarriageContraptionEntity)(Object)this;
        if(com.roften.avilixlogger.compat.create.CreateCargoAudit.restoreLocked(self))return;
        if (!com.roften.avilixlogger.compat.create.CreateCargoAudit.active(self)) { original.call(pos, after); return; }
        var before = com.roften.avilixlogger.compat.create.CreateStructureAudit.beforeChange(self, pos);
        original.call(pos, after);
        com.roften.avilixlogger.compat.create.CreateStructureAudit.changed(self, pos, before);
    }

    @Inject(method = "startControlling", at = @At("RETURN"), require = 0)
    private void avilixlogger$controlStart(BlockPos controlsLocalPos, Player player, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!Boolean.TRUE.equals(cir.getReturnValue())) return;
            if (!(player instanceof ServerPlayer sp)) return;

            Entity self = (Entity) (Object) this;
            if (!(self.level() instanceof ServerLevel sl)) return;

            Object carriage = invoke0(this, "getCarriage");
            Object train = readField(carriage, "train");
            if (train == null) return;

            CreateTrainsCompatHooks.logControlStart(sl, train, sp.getUUID(), sp.getGameProfile().getName(), self.blockPosition());
        } catch (Throwable ignored) {}
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

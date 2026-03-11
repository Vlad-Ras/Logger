package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.compat.create.CreateTrainsCompatHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.lang.reflect.Method;

@Mixin(targets = "com.simibubi.create.content.trains.station.StationBlockEntity")
public class StationBlockEntityMixin {

    @Redirect(
            method = "assemble",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/GlobalRailwayManager;addTrain(Lcom/simibubi/create/content/trains/entity/Train;)V"
            ),
            require = 0
    )
    private void avilixlogger$addTrainAndLog(@Coerce Object railwaysManager, @Coerce Object train) {
        // вызвать оригинал
        tryInvoke1(railwaysManager, "addTrain", train);

        // залогировать
        try {
            BlockEntity be = (BlockEntity) (Object) this;
            if (!(be.getLevel() instanceof ServerLevel sl)) return;

            BlockPos at = be.getBlockPos();
            CreateTrainsCompatHooks.logTrainAssembled(sl, train, at);
        } catch (Throwable ignored) {}
    }

    @Redirect(
            method = "tryDisassembleTrain",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/entity/Train;disassemble(Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/server/level/ServerPlayer;)Z"
            ),
            require = 0
    )
    private boolean avilixlogger$disassembleAndLog(@Coerce Object train,
                                                   Object assemblyDirection,
                                                   BlockPos pos,
                                                   ServerPlayer sender) {
        boolean ok = (boolean) tryInvoke3Bool(train, "disassemble", assemblyDirection, pos, sender);

        if (ok) {
            try {
                BlockEntity be = (BlockEntity) (Object) this;
                if (!(be.getLevel() instanceof ServerLevel sl)) return ok;

                BlockPos at = be.getBlockPos();
                var actorUuid = sender != null ? sender.getUUID() : null;
                var actorName = sender != null ? sender.getGameProfile().getName() : null;

                CreateTrainsCompatHooks.logTrainDisassembled(sl, train, actorUuid, actorName, at);
            } catch (Throwable ignored) {}
        }

        return ok;
    }

    private static void tryInvoke1(Object instance, String name, Object a1) {
        if (instance == null) return;
        try {
            for (Method m : instance.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() != 1) continue;
                m.invoke(instance, a1);
                return;
            }
        } catch (Throwable ignored) {}
    }

    private static Object tryInvoke3Bool(Object instance, String name, Object a1, Object a2, Object a3) {
        if (instance == null) return Boolean.FALSE;
        try {
            for (Method m : instance.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() != 3) continue;
                Object r = m.invoke(instance, a1, a2, a3);
                return r instanceof Boolean b ? b : Boolean.FALSE;
            }
        } catch (Throwable ignored) {}
        return Boolean.FALSE;
    }
}
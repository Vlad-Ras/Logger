package com.roften.avilixlogger.compat.opac.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.opac.OpenPacStateAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import com.roften.avilixlogger.core.CauseContext;
import com.roften.avilixlogger.compat.opac.OpenPacTaskCause;
@Pseudo
@Mixin(targets = "xaero.pac.common.server.task.ServerSpreadoutQueuedTaskHandler", remap = false)
public abstract class QueueCauseMixin {
    @WrapMethod(method = "addTask(Lxaero/pac/common/server/task/IServerSpreadoutQueuedTask;Lxaero/pac/common/server/IServerData;)Z", remap = false)
    private boolean avilixlogger$enqueue(@Coerce Object task, @Coerce Object data, Operation<Boolean> original) {
        if (OpenPacStateAudit.available() && task instanceof OpenPacTaskCause bound) bound.avilixlogger$bindCause(CauseContext.peek());
        return original.call(task, data);
    }
}

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
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;
@Pseudo
@Mixin(targets = "xaero.pac.common.server.task.ServerSpreadoutTaskHandler", remap = false)
public abstract class TaskExecutionMixin {
    @WrapOperation(method = "onTick(Lxaero/pac/common/server/IServerData;)V", at = @At(value = "INVOKE", target = "Lxaero/pac/common/server/task/IServerSpreadoutTask;onTick(Lxaero/pac/common/server/IServerData;Ljava/lang/Object;ILjava/util/List;)V"), remap = false)
    private void avilixlogger$execute(@Coerce Object task, @Coerce Object data, Object holder, int budget, List<?> children, Operation<Void> original) {
        if (!OpenPacStateAudit.available() || !com.roften.avilixlogger.LoggerConfig.isEnabled()) { original.call(task, data, holder, budget, children); return; }
        var actor = task instanceof OpenPacTaskCause bound ? bound.avilixlogger$cause() : null;
        int firstChild = children.size();
        try (var cause = actor == null ? CauseContext.unattributed() : CauseContext.push(actor)) {
            try { original.call(task, data, holder, budget, children); }
            finally { for (int i = firstChild; i < children.size(); i++) if (children.get(i) instanceof OpenPacTaskCause child) child.avilixlogger$bindCause(CauseContext.peek()); }
        }
    }
}

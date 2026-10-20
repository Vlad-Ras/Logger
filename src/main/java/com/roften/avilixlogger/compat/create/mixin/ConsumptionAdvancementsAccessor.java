package com.roften.avilixlogger.compat.create.mixin;
import net.minecraft.advancements.*;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.*;
import java.util.*;
@Mixin(PlayerAdvancements.class)
public interface ConsumptionAdvancementsAccessor {
    @Accessor("progress") Map<AdvancementHolder,AdvancementProgress> avilixlogger$progress();
    @Accessor("progressChanged") Set<AdvancementHolder> avilixlogger$changed();
    @Invoker("markForVisibilityUpdate") void avilixlogger$visibility(AdvancementHolder holder);
    @Invoker("registerListeners") void avilixlogger$listen(AdvancementHolder holder);
}

package com.roften.avilixlogger.compat.create.mixin;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.advancements.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.*;
import java.util.*;
@Mixin(PlayerAdvancements.class)
public interface PlayerProgressAccessor {
    @Accessor("progress") Map<AdvancementHolder,AdvancementProgress> avilixlogger$progress();
    @Accessor("progressChanged") Set<AdvancementHolder> avilixlogger$changed();
    @Accessor("player") ServerPlayer avilixlogger$player();
    @Invoker("registerListeners") void avilixlogger$listen(ServerAdvancementManager manager);
    @Invoker("markForVisibilityUpdate") void avilixlogger$visibility(AdvancementHolder holder);
}

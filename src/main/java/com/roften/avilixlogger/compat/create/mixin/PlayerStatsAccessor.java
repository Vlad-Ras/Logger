package com.roften.avilixlogger.compat.create.mixin;
import net.minecraft.stats.*;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(StatsCounter.class)
public interface PlayerStatsAccessor {
    @Accessor("stats") Object2IntMap<Stat<?>> avilixlogger$stats();
}

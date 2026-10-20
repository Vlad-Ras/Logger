package com.roften.avilixlogger.compat.create.mixin;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.stats.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(StatsCounter.class)
public interface ConsumptionStatsAccessor {
    @Accessor("stats") Object2IntMap<Stat<?>> avilixlogger$stats();
}

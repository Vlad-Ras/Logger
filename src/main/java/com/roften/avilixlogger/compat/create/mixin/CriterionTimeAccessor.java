package com.roften.avilixlogger.compat.create.mixin;
import net.minecraft.advancements.CriterionProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(CriterionProgress.class)
public interface CriterionTimeAccessor {
    @Accessor("obtained") void avilixlogger$obtained(java.time.Instant time);
}

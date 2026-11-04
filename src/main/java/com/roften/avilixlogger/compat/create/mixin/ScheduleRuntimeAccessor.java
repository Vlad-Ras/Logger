package com.roften.avilixlogger.compat.create.mixin;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=ScheduleRuntime.class,remap=false)
public interface ScheduleRuntimeAccessor {
    @Accessor("cooldown") int avilixlogger$cooldown();
    @Accessor("cooldown") void avilixlogger$cooldown(int value);
}

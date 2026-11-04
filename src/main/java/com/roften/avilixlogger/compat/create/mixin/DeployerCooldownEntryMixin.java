package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.compat.create.CreateCooldownEntryAccess;
import org.spongepowered.asm.mixin.*;

@Mixin(targets="net.minecraft.world.item.ItemCooldowns$CooldownInstance")
public abstract class DeployerCooldownEntryMixin implements CreateCooldownEntryAccess {
    @Shadow @Final @Mutable private int startTime;
    @Shadow @Final @Mutable private int endTime;
    public int avilixlogger$start(){return startTime;}
    public int avilixlogger$end(){return endTime;}
    public void avilixlogger$times(int start,int end){startTime=start;endTime=end;}
}

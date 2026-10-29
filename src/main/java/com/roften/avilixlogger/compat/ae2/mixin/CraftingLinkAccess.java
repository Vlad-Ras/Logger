package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.api.networking.crafting.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets="appeng.crafting.CraftingLink", remap=false)
public interface CraftingLinkAccess {
    @Accessor("req") ICraftingRequester avilixlogger$requester();
    @Accessor("cpu") ICraftingCPU avilixlogger$cpu();
    @Accessor("canceled") boolean avilixlogger$rawCancelled();
}

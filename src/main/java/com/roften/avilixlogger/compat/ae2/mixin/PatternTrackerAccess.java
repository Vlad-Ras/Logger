package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.helpers.patternprovider.PatternContainer;
import appeng.api.inventories.InternalInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets="appeng.menu.implementations.PatternAccessTermMenu$ContainerTracker", remap=false)
public interface PatternTrackerAccess {
    @Accessor("container") PatternContainer avilixlogger$container();
    @Accessor("serverId") long avilixlogger$serverId();
    @Accessor("server") InternalInventory avilixlogger$server();
}

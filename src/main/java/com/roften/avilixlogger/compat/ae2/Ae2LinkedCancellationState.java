package com.roften.avilixlogger.compat.ae2;

import appeng.api.networking.crafting.ICraftingLink;

/** Constant-size native nexus view. Read only after authorization and server-thread guards. */
public interface Ae2LinkedCancellationState {
    ICraftingLink avilixlogger$requestLink();
    ICraftingLink avilixlogger$cpuLink();
    boolean avilixlogger$cancelled();
    boolean avilixlogger$done();
}

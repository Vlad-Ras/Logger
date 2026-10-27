package com.roften.avilixlogger.compat.ae2;

import appeng.api.inventories.InternalInventory;

/** Shared action owner for version-specific remote entry points. */
public interface Ae2RemoteMenuCapture {
    boolean avilixlogger$remoteBefore(boolean liveReads);
    void avilixlogger$remoteAfter(boolean owner);
    InternalInventory avilixlogger$observeRemote(Object tracker, InternalInventory inventory);
}

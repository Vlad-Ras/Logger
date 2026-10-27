package com.roften.avilixlogger.core;

import com.roften.avilixlogger.compat.supplementaries.SupplementariesAutomationAudit;
import com.roften.avilixlogger.compat.supplementaries.SupplementariesTextAudit;
import net.minecraft.nbt.CompoundTag;

/** Closed, world-free rules for filtering detached evidence before worker-side encoding. */
public enum SnapshotDeltaPolicy {
    SIGN_TEXT, SUPPLEMENTARIES_TEXT, SUPPLEMENTARIES_DISPLAY;

    boolean changed(CompoundTag before,CompoundTag after){
        return switch(this){
            case SIGN_TEXT -> SignTextAudit.changed(before,after);
            case SUPPLEMENTARIES_TEXT -> SupplementariesTextAudit.changed(before,after);
            case SUPPLEMENTARIES_DISPLAY -> SupplementariesAutomationAudit.changed(before,after);
        };
    }
}

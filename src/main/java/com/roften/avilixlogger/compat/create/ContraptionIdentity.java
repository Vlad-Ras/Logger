package com.roften.avilixlogger.compat.create;
import java.util.UUID;
/** Logical identity is independent of the current entity representation. */
public interface ContraptionIdentity {
    UUID avilixlogger$identity();
    void avilixlogger$identity(UUID id);
}

package com.roften.avilixlogger.core;

/**
 * Optional health signal for composite storages.
 * Implementations must keep this non-blocking: no DB calls from isLikelyAvailable().
 */
public interface HealthAwareLogStorage extends LogStorage {
    /** True when the backend is considered safe to receive new writes. */
    boolean isLikelyAvailable();

    /** Human-readable backend name for logs. */
    String storageName();
}

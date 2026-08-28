package com.roften.avilixlogger.api;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.core.AdaptiveLogDiagnostics;
import com.roften.avilixlogger.core.LogEntry;

import java.util.Comparator;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe registry for optional semantic adapters supplied by other mods. */
public final class LogAdapterRegistry {
    private static final CopyOnWriteArrayList<LogSemanticAdapter> ADAPTERS = new CopyOnWriteArrayList<>();
    private static final Set<String> WARNED_FAILURES = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean DISCOVERED = new AtomicBoolean();

    private LogAdapterRegistry() {}

    public static void register(LogSemanticAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        String adapterId = safeId(adapter);
        for (LogSemanticAdapter existing : ADAPTERS) {
            if (existing == adapter || safeId(existing).equals(adapterId)) return;
        }
        ADAPTERS.add(adapter);
        ADAPTERS.sort(Comparator.comparingInt(LogSemanticAdapter::priority).reversed()
                .thenComparing(LogAdapterRegistry::safeId));
        AvilixLoggerMod.LOGGER.info("[AvilixLogger] Registered semantic adapter: {}", adapterId);
    }

    public static void unregister(LogSemanticAdapter adapter) {
        if (adapter != null) ADAPTERS.remove(adapter);
    }

    /** Discover Java service providers once. This is called from the storage initializer thread. */
    public static void discover() {
        if (!DISCOVERED.compareAndSet(false, true)) return;
        try {
            ServiceLoader.load(LogSemanticAdapter.class, LogSemanticAdapter.class.getClassLoader())
                    .forEach(LogAdapterRegistry::register);
        } catch (Throwable error) {
            AdaptiveLogDiagnostics.adapterFailure("service-loader");
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Semantic adapter discovery failed", error);
        }
    }

    public static void enrich(LogEntry entry) {
        if (entry == null || ADAPTERS.isEmpty()) return;
        for (LogSemanticAdapter adapter : ADAPTERS) {
            try {
                if (adapter.supports(entry)) adapter.enrich(entry);
            } catch (Throwable error) {
                String adapterId = safeId(adapter);
                AdaptiveLogDiagnostics.adapterFailure(adapterId);
                if (WARNED_FAILURES.add(adapterId)) {
                    AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Semantic adapter '{}' failed; core logging continues",
                            adapterId, error);
                }
            }
        }
    }

    public static int size() {
        return ADAPTERS.size();
    }

    private static String safeId(LogSemanticAdapter adapter) {
        try {
            String id = adapter.id();
            return id == null || id.isBlank() ? adapter.getClass().getName() : id;
        } catch (Throwable ignored) {
            return adapter == null ? "unknown" : adapter.getClass().getName();
        }
    }
}

package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-world persistent settings for AvilixLogger.
 * Currently stores the inspect tool item id (overridable at runtime via command).
 */
public final class LoggerServerData extends SavedData {

    private static final String DATA_NAME = "avilixlogger_data";
    private static final String KEY_INSPECT_TOOL = "inspectToolItemId";
    private static final String KEY_PRESETS = "presets";
    private static final String KEY_CAPTURE_MASK = "guiCaptureDisabledMask";
    private static final String KEY_CAPTURE_REVISION = "guiCaptureRevision";

    private static final long REPORT_INTERVAL_MS = Duration.ofHours(24).toMillis();
    private static final int MAX_REPORT_COOLDOWNS = 4096;
    private final Map<UUID, Long> reportCooldowns = new HashMap<>();

    private String inspectToolItemId;
    private long guiCaptureDisabledMask;
    private int guiCaptureRevision;
    private java.util.Set<String> disabledHandlers = java.util.Set.of();

    /** Stored command argument presets (name -> raw args string). */
    private final java.util.Map<String, String> presets = new java.util.LinkedHashMap<>();

    private LoggerServerData() {
        this.inspectToolItemId = LoggerConfig.inspectToolItemId();
    }

    public static LoggerServerData get(ServerLevel level) {
                return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(LoggerServerData::new, LoggerServerData::load), DATA_NAME);
    }

    public static LoggerServerData load(CompoundTag tag, HolderLookup.Provider provider) {
        LoggerServerData d = new LoggerServerData();
        if (tag.contains(KEY_INSPECT_TOOL)) {
            d.inspectToolItemId = tag.getString(KEY_INSPECT_TOOL);
        }
        d.guiCaptureDisabledMask = tag.getLong(KEY_CAPTURE_MASK) & CaptureTypePolicy.ALL_DISABLED;
        var handlers = tag.getList("guiDisabledHandlers", net.minecraft.nbt.Tag.TAG_STRING);
        var keys = new java.util.HashSet<String>();
        for (int i = 0; i < Math.min(2048, handlers.size()); i++) {
            String key = handlers.getString(i);
            if (!key.isBlank() && key.length() <= 256) keys.add(key);
        }
        d.disabledHandlers = java.util.Set.copyOf(keys);
        d.guiCaptureRevision = Math.max(0, tag.getInt(KEY_CAPTURE_REVISION));
        if (tag.contains(KEY_PRESETS, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            CompoundTag p = tag.getCompound(KEY_PRESETS);
            for (String k : p.getAllKeys()) {
                String v = p.getString(k);
                if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                    d.presets.put(k.toLowerCase(java.util.Locale.ROOT), v);
                }
            }
        }
        var cooldowns = tag.getList("clientReportCooldowns", Tag.TAG_COMPOUND);
        long now = System.currentTimeMillis();
        for (int i = 0; i < Math.min(MAX_REPORT_COOLDOWNS, cooldowns.size()); i++) {
            var entry = cooldowns.getCompound(i);
            long until = entry.getLong("until");
            if (entry.hasUUID("player") && until > now) d.reportCooldowns.put(entry.getUUID("player"), until);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        if (inspectToolItemId != null) tag.putString(KEY_INSPECT_TOOL, inspectToolItemId);
        tag.putLong(KEY_CAPTURE_MASK, guiCaptureDisabledMask);
        var handlers = new net.minecraft.nbt.ListTag();
        disabledHandlers.stream().sorted().forEach(key -> handlers.add(net.minecraft.nbt.StringTag.valueOf(key)));
        tag.put("guiDisabledHandlers", handlers);
        tag.putInt(KEY_CAPTURE_REVISION, guiCaptureRevision);
        CompoundTag p = new CompoundTag();
        for (var e : presets.entrySet()) {
            if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null && !e.getValue().isBlank()) {
                p.putString(e.getKey(), e.getValue());
            }
        }
        tag.put(KEY_PRESETS, p);
        pruneClientReportCooldowns(System.currentTimeMillis());
        if (!reportCooldowns.isEmpty()) {
            var cooldowns = new ListTag();
            reportCooldowns.forEach((player, until) -> {
                var entry = new CompoundTag();
                entry.putUUID("player", player);
                entry.putLong("until", until);
                cooldowns.add(entry);
            });
            tag.put("clientReportCooldowns", cooldowns);
        }
        return tag;
    }

    /** Zero reserves a collection, positive is the next allowed timestamp, -1 means capacity reached. */
    public long reserveClientReport(UUID player, long now) {
        long until = reportCooldowns.getOrDefault(player, 0L);
        if (until > now) return until;
        if (reportCooldowns.size() >= MAX_REPORT_COOLDOWNS) {
            pruneClientReportCooldowns(now);
            if (reportCooldowns.size() >= MAX_REPORT_COOLDOWNS) return -1;
        }
        reportCooldowns.put(player, now + REPORT_INTERVAL_MS);
        setDirty();
        return 0;
    }

    private void pruneClientReportCooldowns(long now) {
        if (reportCooldowns.values().removeIf(until -> until <= now)) setDirty();
    }

    public long captureMask() { return guiCaptureDisabledMask; }
    public int captureRevision() { return guiCaptureRevision; }
    /** Server thread only: durable level SavedData and immediate volatile hot-path update. */
    public void setCaptureMask(long mask) {
        setCaptureSettings(mask, disabledHandlers);
    }

    public java.util.Set<String> disabledHandlers() { return disabledHandlers; }

    public void setCaptureSettings(long mask, java.util.Set<String> handlers) {
        long next = mask & CaptureTypePolicy.ALL_DISABLED;
        boolean changed = guiCaptureDisabledMask != next || !disabledHandlers.equals(handlers);
        guiCaptureDisabledMask = next;
        disabledHandlers = java.util.Set.copyOf(handlers);
        CaptureTypePolicy.install(next);
        CaptureHandlers.install(disabledHandlers);
        if (changed) {
            guiCaptureRevision++;
            setDirty();
        }
    }

    public String getInspectToolItemId() {
        return inspectToolItemId != null && !inspectToolItemId.isBlank() ? inspectToolItemId : LoggerConfig.inspectToolItemId();
    }

    public void setInspectToolItemId(String id) {
        this.inspectToolItemId = id;
        setDirty();
    }

    public void resetInspectToolItemId() {
        this.inspectToolItemId = LoggerConfig.inspectToolItemId();
        setDirty();
    }

    public java.util.Map<String, String> getPresets() {
        return java.util.Collections.unmodifiableMap(presets);
    }

    public String getPreset(String name) {
        if (name == null) return null;
        return presets.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    public boolean setPreset(String name, String args) {
        if (name == null || name.isBlank() || args == null || args.isBlank()) return false;
        presets.put(name.toLowerCase(java.util.Locale.ROOT), args.trim());
        setDirty();
        return true;
    }

    public boolean removePreset(String name) {
        if (name == null || name.isBlank()) return false;
        String k = name.toLowerCase(java.util.Locale.ROOT);
        boolean removed = presets.remove(k) != null;
        if (removed) setDirty();
        return removed;
    }
}

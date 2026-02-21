package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;
import net.minecraft.nbt.CompoundTag;
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

    private String inspectToolItemId;

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
        if (tag.contains(KEY_PRESETS, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            CompoundTag p = tag.getCompound(KEY_PRESETS);
            for (String k : p.getAllKeys()) {
                String v = p.getString(k);
                if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                    d.presets.put(k.toLowerCase(java.util.Locale.ROOT), v);
                }
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        if (inspectToolItemId != null) tag.putString(KEY_INSPECT_TOOL, inspectToolItemId);
        CompoundTag p = new CompoundTag();
        for (var e : presets.entrySet()) {
            if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null && !e.getValue().isBlank()) {
                p.putString(e.getKey(), e.getValue());
            }
        }
        tag.put(KEY_PRESETS, p);
        return tag;
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

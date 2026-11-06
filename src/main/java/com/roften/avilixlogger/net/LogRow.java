package com.roften.avilixlogger.net;

import com.roften.avilixlogger.core.ActionType;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * One row shown in the optional client GUI.
 *
 * <p>GUI may aggregate multiple raw log entries into a single row to avoid spam.
 * In that case {@code aggregated=true} and {@code rawIds} contains the underlying SQL ids.
 */
public record LogRow(long id,
                     String dim,
                     int x,
                     int y,
                     int z,
                     Component line,
                     boolean aggregated,
                     long[] rawIds,
                     List<Component> groupedLines,
                     ActionType action,
                     String originMod) {

    public LogRow {
        originMod = originMod == null ? "" : originMod;
        line = line == null ? Component.empty() : line;
        rawIds = rawIds == null ? new long[0] : rawIds;
        groupedLines = groupedLines == null ? List.of() : List.copyOf(groupedLines);
    }

    public LogRow(long id, String dim, int x, int y, int z, Component line, boolean aggregated,
                  long[] rawIds, List<Component> groupedLines) {
        this(id, dim, x, y, z, line, aggregated, rawIds, groupedLines, null, "");
    }

    /** Compatibility constructor for ordinary rows and existing call sites. */
    public LogRow(long id, String dim, int x, int y, int z, Component line, boolean aggregated, long[] rawIds) {
        this(id, dim, x, y, z, line, aggregated, rawIds, List.of());
    }

    public static LogRow single(long id, String dim, int x, int y, int z, Component line) {
        return new LogRow(id, dim, x, y, z, line, false, new long[]{id}, List.of());
    }
}

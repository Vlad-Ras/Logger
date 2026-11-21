package com.roften.avilixlogger.core;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.IdentityHashMap;

/** Forced checkpoint on the server thread; always preserves every dimension's save setting. */
public final class RecoveryCheckpoint {
    private RecoveryCheckpoint() {}

    public static void flush(MinecraftServer server) {
        if (server == null || !server.isSameThread())
            throw new IllegalStateException("Сохранение восстановления вызвано вне серверного потока");
        var previous = new IdentityHashMap<ServerLevel, Boolean>();
        try {
            for (ServerLevel level : server.getAllLevels()) {
                previous.put(level, level.noSave);
                level.noSave = true;
            }
            // force=true saves even with noSave=true (save-all flush semantics).
            if (!server.saveEverything(false, true, true))
                throw new IllegalStateException("Не удалось сохранить мир перед/после восстановления");
        } finally {
            previous.forEach((level, disabled) -> level.noSave = disabled);
        }
    }
}

package com.roften.avilixlogger.core;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Permission bridge for LuckPerms (if present) with OP-level fallback.
 *
 * Nodes are plain strings, e.g. "avilixlogger.command.lookup".
 *
 * Supported "multi" / wildcard nodes that grant access to everything:
 * - "avilixlogger.command.all"
 * - "avilixlogger.command.*"
 * - "avilixlogger.*"
 */
public final class PermissionUtil {

    private PermissionUtil() {}

    public static boolean has(CommandSourceStack source, String node, int opFallbackLevel) {
        if (source == null) return false;

        // Console / command blocks: keep vanilla semantics.
        if (!(source.getEntity() instanceof ServerPlayer sp)) {
            return source.hasPermission(opFallbackLevel);
        }

        // Try LuckPerms API via reflection (no hard dependency).
        try {
            Class<?> providerClz = Class.forName("net.luckperms.api.LuckPermsProvider");
            Method getM = providerClz.getMethod("get");
            Object luckPerms = getM.invoke(null);

            Object userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
            UUID uuid = sp.getUUID();
            Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, uuid);
            if (user != null) {
                Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
                Object permData = cachedData.getClass().getMethod("getPermissionData").invoke(cachedData);

                // Single permission that enables all commands
                if (check(permData, "avilixlogger.command.all")) return true;

                // Common LuckPerms wildcard patterns
                if (check(permData, "avilixlogger.command.*")) return true;
                if (check(permData, "avilixlogger.*")) return true;

                // Specific node for this command
                return check(permData, node);
            }
        } catch (Throwable ignored) {
            // ignore and fallback
        }

        return source.hasPermission(opFallbackLevel);
    }

    private static boolean check(Object permData, String node) {
        try {
            Object result = permData.getClass().getMethod("checkPermission", String.class).invoke(permData, node);
            Object b = result.getClass().getMethod("asBoolean").invoke(result);
            if (b instanceof Boolean bb) return bb.booleanValue();
            return Boolean.TRUE.equals(b);
        } catch (Throwable t) {
            return false;
        }
    }
}

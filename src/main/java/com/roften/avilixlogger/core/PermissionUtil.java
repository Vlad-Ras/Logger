package com.roften.avilixlogger.core;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Permission bridge for LuckPerms (if present) with OP-level fallback.
 *
 * Supports exact nodes plus parent wildcards / .all expansions, e.g. for
 * avilixlogger.command.plane.owner.set we will also honor:
 * - avilixlogger.command.plane.owner.*
 * - avilixlogger.command.plane.owner.all
 * - avilixlogger.command.plane.*
 * - avilixlogger.command.plane.all
 * - avilixlogger.command.*
 * - avilixlogger.command.all
 * - avilixlogger.*
 * - avilixlogger.all
 */
public final class PermissionUtil {

    private PermissionUtil() {}

    public static boolean has(CommandSourceStack source, String node, int opFallbackLevel) {
        if (source == null) return false;

        // Console / command blocks: keep vanilla semantics.
        if (!(source.getEntity() instanceof ServerPlayer sp)) {
            return source.hasPermission(opFallbackLevel);
        }

        Object permData = permissionData(sp);
        if (permData != null) {
            return checkAny(permData, node);
        }

        return source.hasPermission(opFallbackLevel);
    }

    public static boolean hasAny(CommandSourceStack source, int opFallbackLevel, String... nodes) {
        if (source == null) return false;

        if (!(source.getEntity() instanceof ServerPlayer sp)) {
            return source.hasPermission(opFallbackLevel);
        }

        Object permData = permissionData(sp);
        if (permData != null) {
            if (nodes != null) {
                for (String node : nodes) {
                    if (checkAny(permData, node)) {
                        return true;
                    }
                }
            }
            return false;
        }

        return source.hasPermission(opFallbackLevel);
    }

    private static Object permissionData(ServerPlayer sp) {
        try {
            Class<?> providerClz = Class.forName("net.luckperms.api.LuckPermsProvider");
            Method getM = providerClz.getMethod("get");
            Object luckPerms = getM.invoke(null);

            Object userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
            UUID uuid = sp.getUUID();
            Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, uuid);
            if (user == null) return null;

            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
            return cachedData.getClass().getMethod("getPermissionData").invoke(cachedData);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean checkAny(Object permData, String node) {
        for (String candidate : expandCandidates(node)) {
            if (checkExact(permData, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> expandCandidates(String node) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (node == null) return out;

        String trimmed = node.trim();
        if (trimmed.isEmpty()) return out;

        out.add(trimmed);

        String[] parts = trimmed.split("\\.");
        for (int len = parts.length - 1; len >= 1; len--) {
            String prefix = String.join(".", java.util.Arrays.copyOf(parts, len));
            out.add(prefix + ".*");
            out.add(prefix + ".all");
        }

        return out;
    }

    private static boolean checkExact(Object permData, String node) {
        try {
            Object result = permData.getClass().getMethod("checkPermission", String.class).invoke(permData, node);
            Object b = result.getClass().getMethod("asBoolean").invoke(result);
            if (b instanceof Boolean bb) return bb;
            return Boolean.TRUE.equals(b);
        } catch (Throwable t) {
            return false;
        }
    }
}

package com.roften.avilixlogger.auth;

import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Method;

/**
 * Fail-closed bridge to the server-only AvilixAuthCore API.
 *
 * <p>The call intentionally uses reflection: Logger is also installed on clients, while
 * AvilixAuthCore and its private-key verification exist only on the dedicated server.</p>
 */
public final class AuthCoreBridge {
    private static final String API_CLASS = "ru.avilix.authcore.api.AvilixAuthApi";
    private static final String API_METHOD = "isAuthorized";

    private static volatile MinecraftServer activeServer;
    private static volatile Method authorizationMethod;
    private static volatile boolean methodLookupAttempted;
    private static volatile String failureDescription = "AvilixAuthCore has not been checked";

    private AuthCoreBridge() {}

    public static boolean attach(MinecraftServer server) {
        activeServer = server;
        return isAuthorized(server);
    }

    public static boolean isAuthorized() {
        return isAuthorized(activeServer);
    }

    public static boolean isAuthorized(MinecraftServer server) {
        if (server == null) {
            failureDescription = "Minecraft server is not available";
            return false;
        }

        Method method = resolveAuthorizationMethod();
        if (method == null) return false;

        try {
            Object result = method.invoke(null, server, AvilixLoggerMod.MOD_ID);
            boolean authorized = Boolean.TRUE.equals(result);
            failureDescription = authorized
                    ? "server authorized by AvilixAuthCore"
                    : "AvilixAuthCore denied this server";
            return authorized;
        } catch (Throwable failure) {
            failureDescription = "AvilixAuthCore API call failed: " + rootMessage(failure);
            return false;
        }
    }

    public static String failureDescription() {
        return failureDescription;
    }

    public static void clear(MinecraftServer server) {
        if (server == null || activeServer == server) activeServer = null;
        failureDescription = "AvilixAuthCore has not been checked";
    }

    private static Method resolveAuthorizationMethod() {
        Method current = authorizationMethod;
        if (current != null) return current;
        if (methodLookupAttempted) return null;

        synchronized (AuthCoreBridge.class) {
            current = authorizationMethod;
            if (current != null) return current;
            if (methodLookupAttempted) return null;
            methodLookupAttempted = true;
            try {
                Class<?> api = Class.forName(API_CLASS, true, AuthCoreBridge.class.getClassLoader());
                current = api.getMethod(API_METHOD, MinecraftServer.class, String.class);
                authorizationMethod = current;
                return current;
            } catch (Throwable failure) {
                failureDescription = "AvilixAuthCore API is unavailable: " + rootMessage(failure);
                return null;
            }
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current == null ? null : current.getMessage();
        return message == null || message.isBlank()
                ? String.valueOf(current == null ? error : current)
                : message;
    }
}

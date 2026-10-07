package com.roften.avilixlogger.auth;

import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.server.MinecraftServer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Fail-closed bridge to the server-only AvilixAuthCore API.
 *
 * <p>The call intentionally uses reflection: Logger is also installed on clients, while
 * AvilixAuthCore and its private-key verification exist only on the dedicated server.
 * Logger does not inspect or compare the server IP/host; authorization is delegated only
 * to the API supplied by AvilixAuthCore.</p>
 */
public final class AuthCoreBridge {
    private static final String API_CLASS = "ru.avilix.authcore.api.AvilixAuthApi";
    private static final String API_METHOD = "isAuthorized";

    private static volatile MinecraftServer activeServer;
    private static volatile MethodHandle authorizationMethod;
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

        MethodHandle method = resolveAuthorizationMethod();
        if (method == null) return false;

        try {
            boolean authorized = (boolean) method.invokeExact(server, AvilixLoggerMod.MOD_ID);
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

    private static MethodHandle resolveAuthorizationMethod() {
        MethodHandle current = authorizationMethod;
        if (current != null) return current;
        if (methodLookupAttempted) return null;

        synchronized (AuthCoreBridge.class) {
            current = authorizationMethod;
            if (current != null) return current;
            if (methodLookupAttempted) return null;
            methodLookupAttempted = true;
            try {
                Class<?> api = Class.forName(API_CLASS, true, AuthCoreBridge.class.getClassLoader());
                current = MethodHandles.publicLookup().unreflect(api.getMethod(API_METHOD, MinecraftServer.class, String.class))
                        .asType(MethodType.methodType(boolean.class, MinecraftServer.class, String.class));
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

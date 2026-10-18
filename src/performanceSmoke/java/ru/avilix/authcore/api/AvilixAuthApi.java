package ru.avilix.authcore.api;
/** TEST ONLY. Isolated fixture API; never included in main classes or release JAR. */
public final class AvilixAuthApi {
    public static boolean allowed;
    public static int calls;
    public static boolean fail;
    public static boolean isAuthorized(net.minecraft.server.MinecraftServer server,String mod){calls++;if(fail)throw new IllegalStateException("test API failure");return allowed;}
}

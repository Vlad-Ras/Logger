package com.roften.avilixlogger.core;

/** Published by the mixin preflight before optional Create classes are linked. */
public final class CreateSupport {
    private static volatile boolean available;
    private static volatile String diagnostic = "Create preflight has not run";
    private CreateSupport() {}
    public static boolean available() { return available; }
    public static String diagnostic() { return diagnostic; }
    public static void publish(boolean enabled, String reason) {
        diagnostic = reason;
        available = enabled;
    }
}

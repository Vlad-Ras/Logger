package com.roften.avilixlogger.compat.scguns;

public final class ScgunsSupport {
    private static volatile boolean available;
    private ScgunsSupport() {}
    public static boolean available(){return available;}
    public static void publish(boolean supported){available=supported;}
}

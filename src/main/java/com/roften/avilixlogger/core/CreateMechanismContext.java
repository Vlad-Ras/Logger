package com.roften.avilixlogger.core;

/** Detached mechanism identity. Enter/restore has no per-callback scope allocation. */
public final class CreateMechanismContext {
    public record Stamp(String blockId, int x, int y, int z, String suffix) {
        public Stamp(String blockId, int x, int y, int z) {
            this(blockId, x, y, z, ":mechanism=" + blockId + "@" + x + "," + y + "," + z);
        }
    }
    private static final ThreadLocal<Stamp> CURRENT = new ThreadLocal<>();
    private CreateMechanismContext() {}
    public static Stamp enter(Stamp stamp) { var previous = CURRENT.get(); CURRENT.set(stamp); return previous; }
    public static void restore(Stamp previous) { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    public static Stamp current() { return CURRENT.get(); }
    public static String decorate(String source) {
        var stamp = CURRENT.get();
        if (stamp == null || CreateContraptionKind.fromSource(source) == CreateContraptionKind.UNKNOWN
                || source.contains(":mechanism=")) return source;
        return source + stamp.suffix();
    }
    public static String description(String source) {
        if (source == null) return null;
        int index = source.indexOf(":mechanism=");
        if (index < 0) return null;
        return source.substring(index + 11);
    }
}

package com.roften.avilixlogger.core;

import java.util.UUID;

/** Exact, exception-safe cause for a mounted Create contraption. Contains no live world objects. */
public final class CartAuditContext {
    public record Stamp(UUID id, UUID owner, String ownerName, String phase, long operation) {
        public String source() { return prefix(id) + phase + ":" + operation; }
    }
    public static final java.util.Set<UUID> LOCKED_ENTITIES = new java.util.HashSet<>();
    public static boolean rollbackActive;
    private static final ThreadLocal<Stamp> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> RESTORING = ThreadLocal.withInitial(() -> false);
    public record RemovedBlock(net.minecraft.core.BlockPos pos, net.minecraft.nbt.CompoundTag be, net.minecraft.nbt.CompoundTag slots) {}
    private static final ThreadLocal<RemovedBlock> REMOVED = new ThreadLocal<>();
    public static void rememberRemovedBlock(net.minecraft.core.BlockPos pos, net.minecraft.nbt.CompoundTag be, net.minecraft.nbt.CompoundTag slots) { REMOVED.set(new RemovedBlock(pos.immutable(), be, slots)); }
    public static RemovedBlock takeRemovedBlock(net.minecraft.core.BlockPos pos) { var old = REMOVED.get(); REMOVED.remove(); return old != null && old.pos.equals(pos) ? old : null; }
    private CartAuditContext() {}
    public static String prefix(UUID id) { return "create:cart:" + id + ":"; }
    public static Stamp current() { return CURRENT.get(); }
    public static Stamp enter(Stamp stamp) { Stamp old = CURRENT.get(); CURRENT.set(stamp); return old; }
    public static void restore(Stamp old) { CURRENT.set(old); REMOVED.remove(); }
    public static boolean restoring() { return RESTORING.get(); }
    public static boolean restoring(boolean value) { boolean old = RESTORING.get(); RESTORING.set(value); return old; }
    public static UUID id(String source) {
        if (source == null || !source.startsWith("create:cart:") || source.length() < 49) return null;
        try { return UUID.fromString(source.substring(12, 48)); } catch (IllegalArgumentException ignored) { return null; }
    }
    public static String phase(String source) {
        if (id(source) == null) return "";
        int end = source.indexOf(':', 49);
        return end < 0 ? source.substring(49) : source.substring(49, end);
    }
    public static void enrich(LogEntry row) {
        Stamp stamp = CURRENT.get();
        if (stamp == null) return;
        if (id(row.source) == null) row.source = stamp.source();
        if (row.actorUuid == null) { row.actorUuid = stamp.owner(); row.actorName = stamp.ownerName(); }
        LogIdGenerator.ensure(row); // Preserve capture order, independent of CPU worker completion order.
    }
}

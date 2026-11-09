package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Thread-local "cause stack" for server-thread actions.
 *
 * Goal: when a mod spawns/removes entities or changes blocks synchronously inside a player action
 * (use/attack/interact/command), we can attribute logs precisely without heuristics.
 */
public final class CauseContext {

    public enum Kind {
        USE_BLOCK,
        USE_ITEM,
        INTERACT_ENTITY,
        ATTACK_ENTITY,
        BREAK_BLOCK,
        PLACE_BLOCK,
        COMMAND,
        OTHER
    }

    public record Cause(UUID actorUuid,
                        String actorName,
                        Kind kind,
                        BlockPos pos,
                        String itemKey,
                        long tsMs) {
    }

    private static final ThreadLocal<Deque<Scope>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static Deque<Scope> serverStack = new ArrayDeque<>();
    private static final int RETAINED_DEPTH = 64;
    // Safe only when no outer scope exists. Never pooled or repurposed for a later action.
    private static final Scope EMPTY_SCOPE = new Scope(null, null);

    private CauseContext() {}

    public static UUID actorUuid(Player player) {
        return player == null || player instanceof net.neoforged.neoforge.common.util.FakePlayer ? null : player.getUUID();
    }
    public static String actorName(Player player) { return actorUuid(player)==null ? "SYSTEM" : player.getName().getString(); }

    public static Scope push(Player player, Kind kind, BlockPos pos, ItemStack used) {
        boolean real = player != null && !(player instanceof net.neoforged.neoforge.common.util.FakePlayer);
        if (!real) return unattributed();
        UUID u = real ? player.getUUID() : null;
        String n = real ? player.getName().getString() : null;
        String itemKey = null;
        try {
            if (used != null && !used.isEmpty()) itemKey = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(used.getItem()));
        } catch (Throwable ignored) {}
        Cause c = new Cause(u, n, kind, pos, itemKey, System.currentTimeMillis());
        return push(real ? c : null);
    }

    public static Scope push(Cause cause) {
        Scope scope = new Scope(cause);
        Deque<Scope> stack = stack();
        // Shrink only after an unusually deep action unwinds, never on normal ticks.
        if (stack.size() == RETAINED_DEPTH) stack.peekLast().releaseStack = true;
        stack.push(scope);
        return scope;
    }

    /** Isolates unrelated simulation from an outer player action. */
    public static Scope unattributed() {
        Deque<Scope> stack = stack();
        // A nested anonymous tick already hides the outer actor. Reusing the immutable
        // no-op token preserves that boundary without allocating one scope per callback.
        return stack.isEmpty() || stack.peek().cause == null ? EMPTY_SCOPE : push((Cause) null);
    }

    public static Cause peek() {
        Deque<Scope> d = stack();
        return d.isEmpty() ? null : d.peek().cause;
    }

    private static Deque<Scope> stack() {
        return ServerCaptureGuard.onServerThread() ? serverStack : STACK.get();
    }

    private static void releaseStack() {
        if (ServerCaptureGuard.onServerThread()) clearServerContext();
        else STACK.remove();
    }

    static void clearServerContext() { serverStack = new ArrayDeque<>(); }

    /** Called on the server thread when its logger lifecycle ends. */
    public static void clearCurrentThread() {
        if (ServerCaptureGuard.onServerThread()) clearServerContext();
        STACK.remove();
    }

    public static final class Scope implements AutoCloseable {
        private boolean closed;
        private boolean releaseStack;
        private Cause cause;
        private final Thread owner;
        private Scope(Cause cause) { this(cause, Thread.currentThread()); }
        private Scope(Cause cause, Thread owner) { this.cause = cause; this.owner = owner; }

        @Override
        public void close() {
            if (this == EMPTY_SCOPE) return;
            if (closed || Thread.currentThread() != owner) return;
            closed = true;
            Deque<Scope> d = stack();
            if (d.peek() == this) d.pop();
            else d.removeFirstOccurrence(this);
            cause = null;
            if (releaseStack) {
                if (d.isEmpty()) releaseStack();
                else d.peekLast().releaseStack = true;
            }
        }
    }
}

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

    private static final ThreadLocal<Deque<Cause>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private CauseContext() {}

    public static Scope push(Player player, Kind kind, BlockPos pos, ItemStack used) {
        UUID u = (player != null) ? player.getUUID() : null;
        String n = (player != null) ? player.getName().getString() : null;
        String itemKey = null;
        try {
            if (used != null && !used.isEmpty()) itemKey = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(used.getItem()));
        } catch (Throwable ignored) {}
        Cause c = new Cause(u, n, kind, pos, itemKey, System.currentTimeMillis());
        STACK.get().push(c);
        return new Scope();
    }

    public static Cause peek() {
        Deque<Cause> d = STACK.get();
        return d.isEmpty() ? null : d.peek();
    }

    public static final class Scope implements AutoCloseable {
        private boolean closed;
        private Scope() {}

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            Deque<Cause> d = STACK.get();
            if (!d.isEmpty()) d.pop();
        }
    }
}

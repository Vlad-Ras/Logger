package com.roften.avilixlogger.core;

import java.util.UUID;
import net.minecraft.world.entity.player.Player;

/** Server-thread identity of a mechanism's technical player, never an inferred nearby actor. */
public final class CreateActorContext {
    public record Stamp(UUID owner, String ownerName, Player technicalPlayer) {}
    private static final ThreadLocal<Stamp> CURRENT = new ThreadLocal<>();
    private CreateActorContext() {}
    public static Stamp current() { return CURRENT.get(); }
    public static Stamp enter(Stamp stamp) { var old = CURRENT.get(); CURRENT.set(stamp); return old; }
    public static void restore(Stamp old) { if (old == null) CURRENT.remove(); else CURRENT.set(old); }
    public static boolean isTechnicalPlayer(Player player) {
        var stamp = CURRENT.get(); return player != null && stamp != null && stamp.technicalPlayer() == player;
    }
}

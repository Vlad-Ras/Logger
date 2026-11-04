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
    /** Only a currently executing, explicitly identified Create mechanism has this
     * owner attribution. Ownership history and nearby players never qualify. */
    public static java.util.UUID ownerUuid(String source) {
        if (CreateMechanismContext.current()==null || CreateContraptionKind.fromSource(source)==CreateContraptionKind.UNKNOWN) return null;
        var cart=CartAuditContext.current();
        if(cart!=null && !cart.id().equals(CreateContraptionKind.id(source)))return null;
        var actor=CURRENT.get();return actor!=null&&actor.owner()!=null?actor.owner():cart==null?null:cart.owner();
    }
    public static String ownerName(String source) {
        var owner=ownerUuid(source);if(owner==null)return null;
        var actor=CURRENT.get();var cart=CartAuditContext.current();
        String name=actor!=null&&owner.equals(actor.owner())?actor.ownerName():cart==null?null:cart.ownerName();
        return name==null||name.isBlank()?owner.toString():name;
    }
    public static void attribute(LogEntry row) {
        if(row==null || row.actorUuid!=null)return;
        var owner=ownerUuid(row.source);if(owner!=null){row.actorUuid=owner;row.actorName=ownerName(row.source);}
    }
    public static boolean isTechnicalPlayer(Player player) {
        var stamp = CURRENT.get(); return player != null && stamp != null && stamp.technicalPlayer() == player;
    }
}

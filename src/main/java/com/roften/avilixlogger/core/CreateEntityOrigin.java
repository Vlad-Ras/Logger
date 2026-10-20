package com.roften.avilixlogger.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import java.util.UUID;

/** A detached origin survives delayed effects, unloading and entity save/load. */
public final class CreateEntityOrigin {
    private static final String KEY = "AvilixCreateOrigin";
    public static final String MIXED_DROP = "AvilixCreateMergedOrigins";
    public record Stamp(String source, UUID owner, String ownerName, UUID actor, String actorName) {}
    private CreateEntityOrigin() {}
    private static boolean supported(Entity entity) {
        return entity instanceof Projectile || entity instanceof FallingBlockEntity || entity instanceof PrimedTnt
                || entity instanceof net.minecraft.world.entity.item.ItemEntity;
    }
    public static Stamp context() {
        String source=MutationSourceResolver.resolveExternalSource();var cart=CartAuditContext.current();
        if(CreateContraptionKind.id(source)==null && cart!=null)source=cart.source();
        if(CreateContraptionKind.id(source)==null)return null;
        var actor=CreateActorContext.current();var cause=CauseContext.peek();
        return new Stamp(CreateMechanismContext.decorate(source),actor!=null?actor.owner():cause!=null?cause.actorUuid():cart==null?null:cart.owner(),
                actor!=null?actor.ownerName():cause!=null?cause.actorName():cart==null?null:cart.ownerName(),
                cause==null?null:cause.actorUuid(),cause==null?null:cause.actorName());
    }
    public static void capture(Entity entity) {
        if (CartAuditContext.restoring() || !supported(entity) || !(entity.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        if(entity instanceof net.minecraft.world.entity.item.ItemEntity drop
                && drop instanceof CreateOriginDataAccess access && access.avilixlogger$originData()!=null
                && access.avilixlogger$originData().contains(MIXED_DROP))return;
        var origin=context();if(origin==null)return;
        UUID owner=origin.owner();String name=origin.ownerName();
        var tag = new CompoundTag(); tag.putString("Source", origin.source());
        if (owner != null) tag.putUUID("Owner", owner);
        if (name != null) tag.putString("OwnerName", name);
        tag.putInt("AttributionFormat", 2);
        if (origin.actor() != null) { tag.putUUID("Actor", origin.actor());
            if (origin.actorName() != null) tag.putString("ActorName", origin.actorName()); }
        entity.getPersistentData().put(KEY, tag);
        if(entity instanceof net.minecraft.world.entity.item.ItemEntity) {
            if(CreateContraptionKind.fromSource(origin.source())==CreateContraptionKind.MINECART)
                entity.getPersistentData().putUUID("AvilixCartDrop",CreateContraptionKind.id(origin.source()));
            else entity.getPersistentData().putString("AvilixCreateDropSource",origin.source());
        }
    }
    public static Stamp read(Entity entity) {
        if (!supported(entity)) return null;
        var data = entity instanceof CreateOriginDataAccess access ? access.avilixlogger$originData()
                : entity instanceof net.minecraft.world.entity.item.ItemEntity ? null : entity.getPersistentData();
        if(data==null)return null;
        if(entity instanceof net.minecraft.world.entity.item.ItemEntity && data.contains(MIXED_DROP))return null;
        if (!data.contains(KEY, CompoundTag.TAG_COMPOUND)) return null;
        var tag = data.getCompound(KEY); String source = tag.getString("Source");
        if (source.length() > 512 || CreateContraptionKind.id(source) == null) return null;
        return new Stamp(source, tag.hasUUID("Owner") ? tag.getUUID("Owner") : null, tag.getString("OwnerName"),
                tag.getInt("AttributionFormat")==2 && tag.hasUUID("Actor") ? tag.getUUID("Actor") : null,
                tag.getInt("AttributionFormat")==2 && tag.hasUUID("Actor") ? tag.getString("ActorName") : null);
    }
    public static CauseContext.Scope cause(Entity entity) {
        var origin = read(entity);
        return origin == null || origin.actor() == null ? CauseContext.unattributed()
                : CauseContext.push(new CauseContext.Cause(origin.actor(), origin.actorName(),
                CauseContext.Kind.OTHER, entity.blockPosition(), null, System.currentTimeMillis()));
    }
}

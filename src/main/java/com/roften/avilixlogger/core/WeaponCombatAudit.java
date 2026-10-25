package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/** Server callbacks only. No own ticks, inventories, capabilities, or global projectile index. */
public final class WeaponCombatAudit {
    private static final ThreadLocal<Fire> FIRE=new ThreadLocal<>();
    private static final ThreadLocal<Reload> RELOAD=new ThreadLocal<>();
    private WeaponCombatAudit() {}
    public static boolean enabled() {return LoggerConfig.isEnabled() && !CartAuditContext.restoring();}
    private static boolean traceEnabled() {return enabled() && CaptureTypePolicy.enabled(3)
            && (LoggerConfig.VALUES.logEntities.get() || LoggerConfig.VALUES.logPlayerLifecycle.get()
                || CaptureTypePolicy.enabled(4) && (LoggerConfig.VALUES.logProjectileShots.get() || LoggerConfig.VALUES.logProjectileHits.get()));}
    public static void failure() {AdaptiveLogDiagnostics.adapterFailure("scguns");}
    public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event){
        if(event.getEntity() instanceof WeaponReloadAccess access)access.avilixlogger$reload(null);
    }
    public static void acceptedFire(){var fire=FIRE.get();if(fire!=null)fire.accepted();}
    public static void aimedFire(){var fire=FIRE.get();if(fire!=null)try{fire.pose=WeaponCombatTrace.Pose.capture(fire.player);}catch(RuntimeException failure){failure();}}

    public static Fire beginFire(ServerPlayer player) {
        if(player==null || !player.server.isSameThread() || !traceEnabled())return null;
        try {var f=new Fire(player,WeaponCombatTrace.Pose.capture(player),FIRE.get());FIRE.set(f);return f;}
        catch(RuntimeException failure){failure();return null;}
    }
    public static final class Fire implements AutoCloseable {
        private final ServerPlayer player;private WeaponCombatTrace.Pose pose;private final Fire parent;
        private WeaponCombatTrace.Shot shot;private boolean accepted;
        private Fire(ServerPlayer p,WeaponCombatTrace.Pose pose,Fire parent){this.player=p;this.pose=pose;this.parent=parent;}
        private WeaponCombatTrace.Shot ensure(ItemStack weapon){if(shot==null)shot=WeaponCombatTrace.capture(player,weapon,pose);return shot;}
        public void accepted(){try {accepted=true;ensure(player.getMainHandItem());}catch(RuntimeException failure){failure();}}
        public void close(){try {
            if(accepted && shot!=null && CaptureTypePolicy.allows(ActionType.PROJECTILE_SHOOT,"mod:scguns",null) && LoggerConfig.VALUES.logProjectileShots.get())
                append(player.serverLevel(),entry(shot,ActionType.PROJECTILE_SHOOT,"shot",null,null));
        }catch(RuntimeException failure){failure();}finally {if(parent==null)FIRE.remove();else FIRE.set(parent);}}
    }
    public static void constructed(Entity projectile,LivingEntity shooter,ItemStack weapon) {
        if(shooter==null || !(projectile.level() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || !(projectile instanceof WeaponTraceAccess access) || !traceEnabled())return;
        try {var fire=FIRE.get();access.avilixlogger$shot(fire!=null && shooter==fire.player?fire.ensure(weapon)
                :WeaponCombatTrace.capture(shooter,weapon,WeaponCombatTrace.Pose.capture(shooter)));}
        catch(RuntimeException failure){failure();}
    }
    public static void joined(EntityJoinLevelEvent event) {
        if(event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread() || !traceEnabled())return;
        try {
            var entity=event.getEntity();if(!(entity instanceof WeaponTraceAccess access))return;
            var fire=FIRE.get();
            if(access.avilixlogger$shot()==null && fire!=null && entity instanceof Projectile projectile && projectile.getOwner()==fire.player)
                access.avilixlogger$shot(fire.ensure(fire.player.getMainHandItem()));
            var shot=access.avilixlogger$shot();
            if(shot!=null && fire==null && CaptureTypePolicy.allows(ActionType.PROJECTILE_SHOOT,"mod:scguns",null) && LoggerConfig.VALUES.logProjectileShots.get())
                append(level,entry(shot,ActionType.PROJECTILE_SHOOT,"projectile_spawn",null,entity));
        }catch(RuntimeException failure){failure();}
    }
    public static boolean traced(Entity entity){return entity instanceof WeaponTraceAccess access && access.avilixlogger$shot()!=null;}
    private static WeaponCombatTrace.Shot shot(DamageSource source) {
        if(source.getDirectEntity() instanceof WeaponTraceAccess access && access.avilixlogger$shot()!=null)return access.avilixlogger$shot();
        var fire=FIRE.get();
        if(fire!=null && source.getEntity()==fire.player && source.typeHolder().unwrapKey().map(k->k.location().getNamespace().equals("scguns")).orElse(false))
            return fire.ensure(fire.player.getMainHandItem());
        return null;
    }
    public static void damage(LivingDamageEvent.Post event) {
        if(!(event.getEntity().level() instanceof ServerLevel level) || !level.getServer().isSameThread() || !enabled()
                || !CaptureTypePolicy.allows(ActionType.PROJECTILE_HIT,"mod:scguns",null) || !LoggerConfig.VALUES.logProjectileHits.get() || !(event.getNewDamage()>0))return;
        try {
            var shot=shot(event.getSource());if(shot==null)return;
            var row=entry(shot,ActionType.PROJECTILE_HIT,"damage",event.getEntity(),event.getSource().getDirectEntity());
            var data=(CompoundTag)row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER);data.putFloat("Damage",event.getNewDamage());
            data.putString("DamageMessageId",event.getSource().getMsgId());row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,data);append(level,row);
        }catch(RuntimeException failure){failure();}
    }
    public static void blockImpact(Entity projectile,BlockHitResult hit) {
        if(!(projectile.level() instanceof ServerLevel level) || !level.getServer().isSameThread() || !enabled()
                || !CaptureTypePolicy.allows(ActionType.PROJECTILE_HIT,"mod:scguns",null) || !LoggerConfig.VALUES.logProjectileHits.get() || !traced(projectile))return;
        try {
            var row=entry(((WeaponTraceAccess)projectile).avilixlogger$shot(),ActionType.PROJECTILE_HIT,"block_hit",null,projectile);
            row.dim=level.dimension().location().toString();row.x=hit.getBlockPos().getX();row.y=hit.getBlockPos().getY();row.z=hit.getBlockPos().getZ();
            var data=(CompoundTag)row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER);
            var impact=new CompoundTag();impact.putDouble("X",hit.getLocation().x);impact.putDouble("Y",hit.getLocation().y);impact.putDouble("Z",hit.getLocation().z);
            impact.putString("Face",hit.getDirection().getName());data.put("Impact",impact);row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,data);append(level,row);
        }catch(RuntimeException failure){failure();}
    }
    public static void death(LogEntry death,LivingEntity victim,DamageSource source) {
        if(!(victim.level() instanceof ServerLevel level) || !level.getServer().isSameThread() || !enabled())return;
        try {
            var shot=shot(source);if(shot==null)return;
            deathEvidence(death,shot,victim,source.getDirectEntity());
            append(level,entry(shot,ActionType.WEAPON_KILL,"kill",victim,source.getDirectEntity()));
        }catch(RuntimeException failure){failure();}
    }
    /** Detached evidence kernel; never changes the death actor or publishes a row. */
    public static void deathEvidence(LogEntry death,WeaponCombatTrace.Shot shot,LivingEntity victim,Entity projectile){
        var data=WeaponCombatTrace.payload(shot,"death",victim);trajectory(data,projectile);
        death.source="mod:scguns:death";death.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,data);
        death.deferSnapshot(LogEntry.SnapshotField.ITEM,shot.weapon());
    }
    /** Pure detached kernel; does not authorize or publish. Victim-death actors stay unchanged. */
    public static LogEntry entry(WeaponCombatTrace.Shot shot,ActionType type,String phase,Entity target,Entity projectile) {
        var pose=target==null?shot.origin():WeaponCombatTrace.Pose.capture(target);
        var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=type;row.dim=pose.dim();
        row.x=(int)Math.floor(pose.x());row.y=(int)Math.floor(pose.y());row.z=(int)Math.floor(pose.z());
        row.actorUuid=shot.actor();row.actorName=shot.actorName();row.source="mod:scguns:"+phase;row.count=1;
        if(target!=null)EntityLogIdentity.capture(row,target);
        var data=WeaponCombatTrace.payload(shot,phase,target);trajectory(data,projectile);
        row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,data);row.deferSnapshot(LogEntry.SnapshotField.ITEM,shot.weapon());
        row.extra="scguns "+phase+"; shooter="+shot.shooter()+"; shot="+shot.id();return row;
    }
    private static void trajectory(CompoundTag data,Entity projectile) {
        if(projectile==null)return;var t=new CompoundTag();t.putUUID("UUID",projectile.getUUID());
        t.putString("Type",EntityType.getKey(projectile.getType()).toString());t.put("Pose",WeaponCombatTrace.Pose.capture(projectile).tag());
        var velocity=projectile.getDeltaMovement();t.putDouble("VelocityX",velocity.x);t.putDouble("VelocityY",velocity.y);t.putDouble("VelocityZ",velocity.z);data.put("Projectile",t);
    }
    private static void append(ServerLevel level,LogEntry row){LoggerRuntime.storage(level).append(row);}
    public static int ammo(ItemStack stack){var data=stack.get(DataComponents.CUSTOM_DATA);return data==null?0:data.getUnsafe().getInt("AmmoCount");}
    public static final class ReloadSession {
        private final java.util.UUID id=java.util.UUID.randomUUID();
        // Native stack identity is retained only on its owning player, never in an async row.
        private final ItemStack stack;
        private CompoundTag weapon;
        private int ammo;
        private ReloadSession(ItemStack stack,CompoundTag weapon,int ammo){this.stack=stack;this.weapon=weapon.copy();this.ammo=ammo;}
    }
    public static Reload beginReload(Player player,ItemStack stack,String phase) {
        if(!(player instanceof ServerPlayer p) || !p.server.isSameThread() || !enabled() || !CaptureTypePolicy.enabled(4) || RELOAD.get()!=null)return null;
        try {var before=NbtSerde.snapshotItemStack(stack,p.registryAccess());if(before==null)return null;
            var session=p instanceof WeaponReloadAccess access?access.avilixlogger$reload():null;
            var r=new Reload(p,stack,phase,before,ammo(stack),WeaponCombatTrace.Pose.capture(p),session);RELOAD.set(r);return r;
        }catch(RuntimeException failure){failure();return null;}
    }
    public record Reload(ServerPlayer player,ItemStack stack,String phase,CompoundTag before,int previousAmmo,WeaponCombatTrace.Pose pose,ReloadSession initialSession) implements AutoCloseable {
        public void close(){try {
            var after=NbtSerde.snapshotItemStack(stack,player.registryAccess());
            if(after!=null && !before.equals(after)){
                var session=initialSession!=null?initialSession:player instanceof WeaponReloadAccess access?access.avilixlogger$reload():null;
                if(session!=null && session.stack==stack){session.weapon=after.copy();session.ammo=ammo(stack);}
                var row=reloadEntry(player,pose,phase,before,after,previousAmmo,ammo(stack));
                if(session!=null && session.stack==stack){var t=(CompoundTag)row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER);t.putUUID("ReloadId",session.id);row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,t);}
                append(player.serverLevel(),row);
            }
        }catch(RuntimeException failure){failure();}finally {RELOAD.remove();}}
    }
    public static void reloadState(Player player,boolean before,boolean after) {
        if(before==after || !(player instanceof ServerPlayer p) || !p.server.isSameThread() || !enabled() || !CaptureTypePolicy.enabled(4))return;
        try {
            var gun=p.getMainHandItem();var item=NbtSerde.snapshotItemStack(gun,p.registryAccess());
            var access=p instanceof WeaponReloadAccess a?a:null;
            var session=access==null?null:access.avilixlogger$reload();
            if(after && item!=null){session=new ReloadSession(gun,item,ammo(gun));if(access!=null)access.avilixlogger$reload(session);}
            if(!after && access!=null)access.avilixlogger$reload(null);
            if(item==null)item=new CompoundTag();
            var evidence=session==null?item:session.weapon;
            var observed=session==null || session.stack==gun?item:evidence;
            var row=reloadEntry(p,WeaponCombatTrace.Pose.capture(p),after?"reload_start":"reload_stop",evidence,observed,session==null?ammo(gun):session.ammo,ammo(gun));
            var t=(CompoundTag)row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER);t.put("HandAtTransition",item.copy());
            if(session!=null)t.putUUID("ReloadId",session.id);
            if(!after && (session==null || session.stack!=gun)){t.remove("AmmoBefore");t.remove("AmmoAfter");t.putBoolean("AmmoUnobservedAtStop",true);}
            row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,t);append(p.serverLevel(),row);
        }catch(RuntimeException failure){failure();}
    }
    public static LogEntry reloadEntry(ServerPlayer player,WeaponCombatTrace.Pose pose,String phase,CompoundTag before,CompoundTag after,int ammoBefore,int ammoAfter) {
        var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.WEAPON_RELOAD;row.actorUuid=CauseContext.actorUuid(player);row.actorName=CauseContext.actorName(player);
        row.dim=pose.dim();row.x=(int)Math.floor(pose.x());row.y=(int)Math.floor(pose.y());row.z=(int)Math.floor(pose.z());row.source="mod:scguns:"+phase;row.count=1;
        var t=new CompoundTag();t.putString("Kind","WeaponCombat");t.putInt("Version",1);t.putString("Provider","scguns");t.putString("Phase",phase);t.put("ActorPose",pose.tag());
        t.put("WeaponBefore",before.copy());t.put("WeaponAfter",after.copy());t.putInt("AmmoBefore",ammoBefore);t.putInt("AmmoAfter",ammoAfter);
        row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,t);row.deferSnapshot(LogEntry.SnapshotField.ITEM,after.copy());row.extra="scguns "+phase;return row;
    }
}

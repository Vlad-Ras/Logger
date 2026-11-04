package com.roften.avilixlogger.compat.scguns;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Native fire scope only: one shared immutable trace per burst, no owner lookup or inventory scan. */
public final class ScgunsTurretAudit {
    private static final ThreadLocal<Fire> CURRENT=new ThreadLocal<>();
    private ScgunsTurretAudit(){}
    public static Fire begin(BlockEntity turret,UUID owner,String name){
        if(!(turret.getLevel() instanceof ServerLevel level) || !WeaponCombatAudit.wantsTrace(level))return null;
        var scope=new Fire(turret,owner,name,CURRENT.get());CURRENT.set(scope);return scope;
    }
    public static WeaponCombatTrace.Shot prepare(Entity projectile,Vec3 muzzle,Vec3 aim){
        var scope=CURRENT.get();
        if(scope==null || !(projectile instanceof WeaponTraceAccess access) || !(projectile.level() instanceof ServerLevel level))return null;
        try {
            if(scope.shot==null)scope.shot=capture(scope.turret,scope.owner,scope.name,level,muzzle,aim);
            access.avilixlogger$shot(scope.shot);return scope.shot;
        }catch(RuntimeException failure){WeaponCombatAudit.failure();return null;}
    }
    public static void spawned(Entity projectile,boolean accepted,WeaponCombatTrace.Shot shot){
        if(shot==null)return;
        if(!accepted){if(projectile instanceof WeaponTraceAccess access)access.avilixlogger$shot(null);return;}
        var scope=CURRENT.get();if(scope!=null && scope.shot==shot){scope.spawned++;scope.projectile=projectile;}
    }
    public static final class Fire implements AutoCloseable {
        private final BlockEntity turret;private final UUID owner;private final String name;private final Fire parent;
        private WeaponCombatTrace.Shot shot;private Entity projectile;private int spawned;
        private Fire(BlockEntity turret,UUID owner,String name,Fire parent){this.turret=turret;this.owner=owner;this.name=name;this.parent=parent;}
        public void close(){try {
            if(spawned>0 && shot!=null && WeaponCombatAudit.enabled() && CaptureTypePolicy.allows(ActionType.PROJECTILE_SHOOT,"mod:scguns",null) && LoggerConfig.VALUES.logProjectileShots.get()){
                var row=WeaponCombatAudit.entry(shot,ActionType.PROJECTILE_SHOOT,"turret_shot",null,projectile);
                var data=(CompoundTag)row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER);data.putInt("ProjectilesSpawned",spawned);
                row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,data);LoggerRuntime.storage((ServerLevel)projectile.level()).append(row);
            }
        }catch(RuntimeException failure){WeaponCombatAudit.failure();}finally {if(parent==null)CURRENT.remove();else CURRENT.set(parent);}}
    }
    /** Detached kernel; does not authorize capture or publish. Aim is the native pre-spread direction. */
    public static WeaponCombatTrace.Shot capture(BlockEntity turret,UUID owner,String name,ServerLevel actionLevel,Vec3 muzzle,Vec3 aim){
        if(!actionLevel.getServer().isSameThread() || !(turret.getLevel() instanceof ServerLevel sourceLevel))throw new IllegalStateException("Turret capture requires server thread");
        double length=aim.length();if(!Double.isFinite(length) || length<1e-12)throw new IllegalArgumentException("Invalid turret aim");
        float yaw=(float)Math.toDegrees(Math.atan2(-aim.x,aim.z));
        float pitch=(float)-Math.toDegrees(Math.atan2(aim.y,Math.hypot(aim.x,aim.z)));
        var pose=new WeaponCombatTrace.Pose(actionLevel.dimension().location().toString(),muzzle.x,muzzle.y,muzzle.z,yaw,pitch,muzzle.y);
        String block=BuiltInRegistries.BLOCK.getKey(turret.getBlockState().getBlock()).toString();
        var mechanism=new CompoundTag();mechanism.putString("Kind","turret");mechanism.putString("Block",block);
        mechanism.putString("Dimension",sourceLevel.dimension().location().toString());var pos=turret.getBlockPos();
        mechanism.putInt("X",pos.getX());mechanism.putInt("Y",pos.getY());mechanism.putInt("Z",pos.getZ());
        if(owner!=null){mechanism.putUUID("Owner",owner);mechanism.putString("OwnerName",name==null?"":name);}
        var weapon=new CompoundTag();weapon.putString("id",block);weapon.putInt("count",1);
        return new WeaponCombatTrace.Shot(UUID.randomUUID(),System.currentTimeMillis(),null,"Турель",null,block,block,pose,weapon,mechanism);
    }
}

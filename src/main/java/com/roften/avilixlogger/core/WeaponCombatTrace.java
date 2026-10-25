package com.roften.avilixlogger.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Detached, versioned evidence. Reading never consults the shooter's current entity. */
public final class WeaponCombatTrace {
    public static final String SAVE_KEY="AvilixLoggerWeaponShot";
    private WeaponCombatTrace() {}

    public record Pose(String dim,double x,double y,double z,float yaw,float pitch,double eyeY) {
        public Pose {
            if(dim==null || dim.isBlank() || !dim.contains(":") || ResourceLocation.tryParse(dim)==null || !Double.isFinite(x) || !Double.isFinite(y)
                    || !Double.isFinite(z) || !Double.isFinite(eyeY) || !Float.isFinite(yaw)
                    || !Float.isFinite(pitch) || Math.abs(x)>30_000_000 || Math.abs(z)>30_000_000
                    || Math.abs(y)>20_000_000 || Math.abs(eyeY)>20_000_000 || Math.abs(pitch)>90)
                throw new IllegalArgumentException("Invalid historical pose");
        }
        public static Pose capture(Entity e) {
            return new Pose(e.level().dimension().location().toString(),e.getX(),e.getY(),e.getZ(),e.getYRot(),e.getXRot(),e.getEyeY());
        }
        public CompoundTag tag() {
            var t=new CompoundTag();t.putString("Dimension",dim);t.putDouble("X",x);t.putDouble("Y",y);t.putDouble("Z",z);
            t.putFloat("Yaw",yaw);t.putFloat("Pitch",pitch);t.putDouble("EyeY",eyeY);return t;
        }
        public static Pose read(CompoundTag t) {
            try {
                if(!t.contains("Dimension",Tag.TAG_STRING))return null;
                for(String key:List.of("X","Y","Z","Yaw","Pitch","EyeY"))if(!t.contains(key,Tag.TAG_ANY_NUMERIC))return null;
                return new Pose(t.getString("Dimension"),t.getDouble("X"),t.getDouble("Y"),t.getDouble("Z"),t.getFloat("Yaw"),t.getFloat("Pitch"),t.getDouble("EyeY"));
            } catch(RuntimeException invalid) { return null; }
        }
        public Pose viewpoint(double observerEyeHeight) { return new Pose(dim,x,eyeY-observerEyeHeight,z,yaw,pitch,eyeY); }
        public String display() {return String.format(Locale.ROOT,"%s: %.5f %.5f %.5f · yaw %.3f · pitch %.3f · eyeY %.5f",dim,x,y,z,yaw,pitch,eyeY);}
    }

    public record Shot(UUID id,long time,UUID actor,String actorName,UUID shooter,String shooterName,String shooterType,Pose origin,CompoundTag weapon) {
        public Shot {
            Objects.requireNonNull(id);Objects.requireNonNull(shooter);Objects.requireNonNull(origin);
            actorName=Objects.requireNonNullElse(actorName,"SYSTEM");shooterName=Objects.requireNonNullElse(shooterName,"");
            Objects.requireNonNull(shooterType);weapon=Objects.requireNonNull(weapon).copy();
        }
        @Override public CompoundTag weapon(){return weapon.copy();}
        public CompoundTag tag() {
            var t=new CompoundTag();t.putInt("Version",1);t.putUUID("Id",id);t.putLong("Time",time);
            if(actor!=null)t.putUUID("Actor",actor);t.putString("ActorName",actorName);t.putUUID("Shooter",shooter);
            t.putString("ShooterName",shooterName);t.putString("ShooterType",shooterType);t.put("Origin",origin.tag());t.put("Weapon",weapon.copy());return t;
        }
        public static Shot read(CompoundTag t) {
            try {
                if(t.getInt("Version")!=1 || !t.hasUUID("Id") || !t.hasUUID("Shooter") || !t.contains("Time",Tag.TAG_LONG)
                        || !t.contains("Weapon",Tag.TAG_COMPOUND))return null;
                var pose=Pose.read(t.getCompound("Origin"));if(pose==null)return null;
                return new Shot(t.getUUID("Id"),t.getLong("Time"),t.hasUUID("Actor")?t.getUUID("Actor"):null,t.getString("ActorName"),
                        t.getUUID("Shooter"),t.getString("ShooterName"),t.getString("ShooterType"),pose,t.getCompound("Weapon"));
            } catch(RuntimeException invalid) {return null;}
        }
    }
    public static Shot capture(LivingEntity shooter,ItemStack weapon,Pose pose) {
        if(shooter.getServer()==null || !shooter.getServer().isSameThread())throw new IllegalStateException("Weapon capture requires server thread");
        var snapshot=NbtSerde.snapshotItemStack(weapon,shooter.registryAccess());if(snapshot==null)return null;
        var player=shooter instanceof net.minecraft.server.level.ServerPlayer p?p:null;
        return new Shot(UUID.randomUUID(),System.currentTimeMillis(),player==null?null:CauseContext.actorUuid(player),
                player==null?"SYSTEM":CauseContext.actorName(player),shooter.getUUID(),EntityLogIdentity.name(shooter),
                net.minecraft.world.entity.EntityType.getKey(shooter.getType()).toString(),pose,snapshot);
    }
    public static CompoundTag payload(Shot shot,String phase,Entity target) {
        var t=new CompoundTag();t.putString("Kind","WeaponCombat");t.putInt("Version",1);t.putString("Provider","scguns");
        t.putString("Phase",phase);t.put("Shot",shot.tag());
        if(target!=null){t.put("TargetPose",Pose.capture(target).tag());t.putUUID("Target",target.getUUID());t.putString("TargetName",EntityLogIdentity.name(target));}
        return t;
    }
    public static CompoundTag context(LogEntry row) {
        var t=NbtSerde.fromSnbt(row.beAfter);
        return t!=null && t.getString("Kind").equals("WeaponCombat") && t.getInt("Version")==1 && t.getString("Provider").equals("scguns")?t:null;
    }
    public static Pose pose(LogEntry row,String side) {
        var t=context(row);if(t==null)return null;
        if(side.equals("target"))return Pose.read(t.getCompound("TargetPose"));
        var shot=Shot.read(t.getCompound("Shot"));return shot==null?Pose.read(t.getCompound("ActorPose")):shot.origin();
    }
    public static List<Component> describe(LogEntry row) {
        var t=context(row);if(t==null)return List.of();var out=new ArrayList<Component>();
        out.add(Component.literal("Scorched Guns · "+t.getString("Phase")));
        var shot=Shot.read(t.getCompound("Shot"));
        if(shot!=null)out.add(Component.literal("Стрелок: "+shot.shooterName()+" · "+shot.shooter()+" · выстрел "+shot.id()+" · "+shot.time()));
        var source=pose(row,"source");var target=pose(row,"target");
        if(source!=null){out.add(Component.literal("Место стрелка: "+source.display()));if(row.id>0){out.add(link(row.id,"source","[На место стрелка]"));out.add(link(row.id,"view","[Восстановить точку взгляда]"));}}
        if(target!=null){out.add(Component.literal("Место цели: "+target.display()));if(row.id>0)out.add(link(row.id,"target","[К цели]"));}
        if(t.contains("Damage"))out.add(Component.literal("Нанесённый урон: "+t.getFloat("Damage")));
        if(t.contains("AmmoBefore"))out.add(Component.literal("Боезапас: "+t.getInt("AmmoBefore")+" → "+t.getInt("AmmoAfter")));
        return List.copyOf(out);
    }
    private static Component link(long id,String side,String label) {
        return Component.literal(label).withStyle(s->s.withColor(net.minecraft.ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/log combatpos "+id+" "+side)));
    }
}

package com.roften.avilixlogger.core;

import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Persisted evidence and malformed historical positions; no world, live capture or DB required. */
public final class WeaponCombatTraceTest {
    public static void run(){
        var pose=new WeaponCombatTrace.Pose("minecraft:overworld",-12.125,70.375,9.875,29.25f,-23.5f,71.645);
        var weapon=new CompoundTag();weapon.putString("id","scguns:auvtomag");weapon.putInt("count",1);
        var data=new CompoundTag();data.putInt("AmmoCount",5);weapon.put("Evidence",data);
        UUID actor=UUID.randomUUID();
        var shot=new WeaponCombatTrace.Shot(UUID.randomUUID(),123456789L,actor,"Shooter",actor,"Shooter","minecraft:player",pose,weapon);
        data.putInt("AmmoCount",0);check(shot.weapon().getCompound("Evidence").getInt("AmmoCount")==5,"input changed immutable weapon evidence");
        var exposed=shot.weapon();exposed.getCompound("Evidence").putInt("AmmoCount",1);
        check(shot.weapon().getCompound("Evidence").getInt("AmmoCount")==5,"accessor exposed nested weapon data");
        var restored=WeaponCombatTrace.Shot.read(shot.tag());check(restored!=null&&restored.id().equals(shot.id())&&restored.origin().equals(pose)&&restored.weapon().equals(shot.weapon()),"save/load changed shot evidence");
        var row=WeaponCombatAudit.entry(restored,ActionType.PROJECTILE_SHOOT,"shot",null,null);row.id=9001;row.materializeSnapshots();
        var replay=GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row),LogEntry.class);
        check(WeaponCombatTrace.pose(replay,"source").equals(pose)&&replay.actorUuid.equals(actor),"journal round trip changed source pose/actor");
        check(row.x==-13&&row.y==70&&row.z==9,"negative summary coordinates truncated instead of floored");
        check(WeaponCombatTrace.weaponLabel(replay)!=null&&WeaponCombatTrace.weaponLabel(replay).getString().contains("scguns:auvtomag"),"lightweight feed lost the weapon used at the shot");
        check(WeaponCombatTrace.pose(replay,"target")==null,"missing target fabricated zero coordinates");
        var view=pose.viewpoint(1.62);check(Math.abs(view.y()+1.62-pose.eyeY())<1e-9&&view.yaw()==pose.yaw()&&view.pitch()==pose.pitch(),"viewpoint lost eye/aim precision");
        for(String field:List.of("X","Y","Z","Yaw","Pitch","EyeY","Dimension")){
            var invalid=pose.tag();invalid.remove(field);check(WeaponCombatTrace.Pose.read(invalid)==null,"missing "+field+" fabricated a pose");
        }
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,30_000_001}){
            var t=pose.tag();t.putDouble("X",invalid);check(WeaponCombatTrace.Pose.read(t)==null,"unsafe coordinate accepted");
        }
        var t=pose.tag();t.putFloat("Pitch",91);check(WeaponCombatTrace.Pose.read(t)==null,"invalid pitch accepted");
        t=pose.tag();t.putString("Dimension","BAD dimension");check(WeaponCombatTrace.Pose.read(t)==null,"invalid dimension accepted");
        var broken=shot.tag();broken.remove("Id");check(WeaponCombatTrace.Shot.read(broken)==null,"unidentified shot accepted");
        broken=shot.tag();broken.putInt("Version",99);check(WeaponCombatTrace.Shot.read(broken)==null,"future evidence version misread");
        check(WeaponCombatTrace.describe(row).stream().anyMatch(c->c.getStyle().getClickEvent()!=null&&c.getStyle().getClickEvent().getValue().equals("/log combatpos 9001 view")),"stored row did not expose exact viewpoint link");
        int mask=CaptureTypePolicy.mask();try{
            CaptureTypePolicy.install(CaptureTypePolicy.changed(0,3,false));check(!CaptureTypePolicy.allows(ActionType.WEAPON_KILL,"mod:scguns",null),"kills bypass entities mask");
            CaptureTypePolicy.install(CaptureTypePolicy.changed(0,4,false));check(!CaptureTypePolicy.allows(ActionType.WEAPON_RELOAD,"mod:scguns",null),"reload bypass items mask");
        }finally{CaptureTypePolicy.install(mask);}
        System.out.println("WEAPON_EVIDENCE_OK: immutable shot, persisted precision/angles, negative coordinates, viewpoint, malformed data and category masks");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

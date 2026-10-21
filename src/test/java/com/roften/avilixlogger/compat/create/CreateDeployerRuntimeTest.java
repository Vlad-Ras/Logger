package com.roften.avilixlogger.compat.create;

import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import java.util.*;

/** Detached history checks: no live level, fake player or Create registry is required. */
public final class CreateDeployerRuntimeTest {
    public static void run(){
        var state=runtime();CreateLivingRuntime.validate(state);var saved=state.copy();
        var projected=CreateLivingRuntime.project(state,112);
        check(state.equals(saved),"projection changed stored history");
        check(projected.getInt("Tick")==112&&projected.getInt("AttackStrength")==17&&projected.getInt("NoAction")==15,"elapsed counters lost");
        check(projected.getInt("HurtTime")==0&&projected.getInt("Invulnerable")==8&&projected.getCompound("Persistent").getShort("HurtTime")==0,"hurt countdown not projected");
        check(projected.getCompound("LastPlayer").isEmpty()&&projected.getCompound("LastMob").isEmpty(),"expired combat references retained");
        state.putInt("AttackStrength",Integer.MAX_VALUE-1);check(CreateLivingRuntime.project(state,Integer.MAX_VALUE).getInt("AttackStrength")==Integer.MAX_VALUE,"elapsed counter overflow");
        reject(()->CreateLivingRuntime.project(saved,99),"reloaded entity accepted");
        for(String field:List.of("Tick","Persistent","Attributes","Tracker","UseItem","LastStamp","LastSource","LastMob","LastVictim","LastPlayer","Flags","Dead","HurtTime","LastHurt")){
            var broken=saved.copy();broken.remove(field);reject(()->CreateLivingRuntime.validate(broken),"missing runtime field accepted: "+field);
        }
        var largeUse=saved.copy();largeUse.getCompound("UseItem").putString("id","minecraft:apple");
        largeUse.getCompound("UseItem").putInt("count",1024);CreateLivingRuntime.validate(largeUse);
        check(CreateLivingRuntime.project(largeUse,112).getCompound("UseItem").getInt("count")==1024,
                "expanded active stack count was lost during projection");
        largeUse.getCompound("UseItem").putInt("count",0);
        reject(()->CreateLivingRuntime.validate(largeUse),"empty active stack accepted");
        var inventory=saved.copy();var persistent=inventory.getCompound("Persistent");
        persistent.putInt("SelectedItemSlot",0);persistent.putInt("XpSeed",0);var items=new ListTag();var offhand=new CompoundTag();
        offhand.putByte("Slot",(byte)150);offhand.putString("id","minecraft:apple");offhand.putInt("count",2048);
        items.add(offhand);persistent.put("Inventory",items);CreateLivingRuntime.validate(inventory);
        items.add(offhand.copy());reject(()->CreateLivingRuntime.validate(inventory),"duplicate runtime inventory slot accepted");
        items.remove(1);offhand.putByte("Slot",(byte)41);
        reject(()->CreateLivingRuntime.validate(inventory),"unknown runtime inventory slot accepted");
        for(String field:List.of("LastDamageTime","Start","End","InCombat","TakingDamage","Entries")){
            var broken=saved.copy();broken.getCompound("Tracker").remove(field);reject(()->CreateLivingRuntime.validate(broken),"missing tracker field accepted: "+field);
        }
        var wrongList=saved.copy();var strings=new ListTag();strings.add(StringTag.valueOf("not an attribute"));wrongList.put("Attributes",strings);
        reject(()->CreateLivingRuntime.validate(wrongList),"wrong list type silently became an empty list");
        var health=saved.copy();health.getCompound("Persistent").putFloat("Health",Float.NaN);reject(()->CreateLivingRuntime.validate(health),"nonfinite health accepted");
        var source=saved.copy();source.getCompound("LastSource").putString("Type","minecraft:player_attack");reject(()->CreateLivingRuntime.validate(source),"incomplete damage source accepted");
        var reference=saved.copy();reference.getCompound("LastMob").remove("Actor");reject(()->CreateLivingRuntime.validate(reference),"ambiguous fake/player reference accepted");
        var modifiers=saved.copy();var attribute=new CompoundTag();attribute.putString("Id","minecraft:generic.max_health");attribute.putDouble("Base",20);
        var values=new ListTag();var modifier=new CompoundTag();modifier.putBoolean("Permanent",false);
        modifier.put("Value",AttributeModifier.CODEC.encodeStart(NbtOps.INSTANCE,new AttributeModifier(ResourceLocation.parse("avilixlogger:test"),5,AttributeModifier.Operation.ADD_VALUE)).result().orElseThrow());
        values.add(modifier);attribute.put("Modifiers",values);modifiers.getList("Attributes",10).add(attribute);CreateLivingRuntime.validate(modifiers);
        values.add(modifier.copy());reject(()->CreateLivingRuntime.validate(modifiers),"duplicate modifier accepted");
        var player=saved.copy();player.put("Food",new CompoundTag());check(CreateLivingRuntime.project(player,112).getInt("NoAction")==0,"player inactivity counter must reset as native aiStep does");
        progressDeltas();cooldowns();legacyAndCurrentPlan();
        System.out.println("CREATE_DEPLOYER_RUNTIME_DATA_OK: bounded complete schema, damaged history rejection, detached aging, cooldowns and legacy/current plans");
    }
    private static CompoundTag stat(String value,int count){var row=new CompoundTag();row.putString("Type","minecraft:custom");row.putString("Value",value);row.putInt("Count",count);return row;}
    private static void progressDeltas(){
        var before=new CompoundTag();before.put("Stats",new ListTag());before.put("Advancements",new ListTag());before.put("Recipes",new CompoundTag());
        var after=before.copy();after.getList("Stats",10).add(stat("minecraft:eat_cake_slice",1));
        var live=after.copy();live.getList("Stats",10).add(stat("minecraft:play_time",20));
        var undone=CreatePlayerProgress.merge(live,after,before);check(undone.getList("Stats",10).size()==1&&undone.getList("Stats",10).getCompound(0).getInt("Count")==20,"undo rewound unrelated play time");
        var changed=live.copy();changed.getList("Stats",10).getCompound(0).putInt("Count",2);reject(()->CreatePlayerProgress.merge(changed,after,before),"changed action statistic accepted");
        var compensated=CreatePlayerProgress.merge(undone,before,after,true);check(compensated.getList("Stats",10).size()==2,"compensation lost action counter or unrelated time");
        var repeat=CreatePlayerProgress.merge(compensated,before,after,true);check(repeat.equals(compensated),"compensation was not idempotent after a partial restore");
    }
    private static void cooldowns(){
        var tag=new CompoundTag();tag.putInt("Tick",4);var entries=new ListTag();var entry=new CompoundTag();
        entry.putString("Item","minecraft:ender_pearl");entry.putInt("Start",0);entry.putInt("End",40);entries.add(entry);tag.put("Entries",entries);CreateCooldownRuntimeAccess.validate(tag);
        entries.add(entry.copy());reject(()->CreateCooldownRuntimeAccess.validate(tag),"duplicate cooldown accepted");entries.remove(1);
        entry.putInt("End",4);reject(()->CreateCooldownRuntimeAccess.validate(tag),"expired cooldown accepted");entry.putInt("End",40);entry.remove("Start");
        reject(()->CreateCooldownRuntimeAccess.validate(tag),"missing cooldown phase accepted");
    }
    private static void legacyAndCurrentPlan(){
        var before=manifest();var after=before.copy();after.getCompound("Hand").getList("Inventory",10).getCompound(0).putInt("count",1);
        check(CreateDeployerRollbackPlan.prepare(before,after).hands().size()==1,"legacy hand rejected");
        var oldTarget=before.copy();var newTarget=after.copy();var id=UUID.randomUUID();
        var target=new CompoundTag();target.putUUID("Entity",id);target.putString("Dimension","minecraft:overworld");
        target.putString("Type","minecraft:sheep");target.putBoolean("Exists",true);
        var entity=new CompoundTag();entity.putUUID("UUID",id);entity.putString("id","minecraft:sheep");target.put("NBT",entity);
        oldTarget.getList("Targets",10).add(target.copy());newTarget.getList("Targets",10).add(target.copy());
        reject(()->CreateDeployerRollbackPlan.prepare(oldTarget,newTarget),"legacy living target accepted without runtime");
        for(var tag:List.of(before,after)){
            tag.putInt("JointFormat",3);var rt=tag.getCompound("Hand").getCompound("Runtime");rt.put("Living",runtime());
            var cooldowns=new CompoundTag();cooldowns.putInt("Tick",0);cooldowns.put("Entries",new ListTag());rt.put("Cooldowns",cooldowns);
        }
        check(CreateDeployerRollbackPlan.prepare(before,after).hands().size()==1,"complete runtime hand rejected");
        var old4=before.copy();var now4=after.copy();
        for(var tag:List.of(old4,now4)){
            tag.putInt("JointFormat",4);for(String key:List.of("WorldCount","TargetCount","CargoCount","UseCount"))tag.putInt(key,0);tag.put("Uses",new ListTag());
            var progress=new CompoundTag();progress.putInt("Format",1);progress.put("Stats",new ListTag());progress.put("Advancements",new ListTag());progress.put("Recipes",new CompoundTag());
            var scores=new CompoundTag();scores.putString("Holder","[Deployer]");scores.put("Objectives",new ListTag());
            tag.getCompound("Hand").getCompound("Runtime").put("Progress",progress);tag.getCompound("Hand").getCompound("Runtime").put("Scores",scores);
        }
        check(CreateDeployerRollbackPlan.prepare(old4,now4).hands().size()==1,"new complete manifest rejected");
        for(String key:List.of("WorldCount","TargetCount","CargoCount","UseCount","Uses")){var broken=now4.copy();broken.remove(key);reject(()->CreateDeployerRollbackPlan.prepare(old4,broken),"missing manifest marker accepted: "+key);}
        var wrongCount=now4.copy();wrongCount.putInt("TargetCount",1);reject(()->CreateDeployerRollbackPlan.prepare(old4,wrongCount),"lost target accepted");
        for(String key:List.of("Progress","Scores")){var broken=now4.copy();broken.getCompound("Hand").getCompound("Runtime").remove(key);reject(()->CreateDeployerRollbackPlan.prepare(old4,broken),"missing progression accepted: "+key);}
        var wrongStats=now4.getCompound("Hand").getCompound("Runtime").getCompound("Progress").copy();var text=new ListTag();text.add(StringTag.valueOf("bad"));wrongStats.put("Stats",text);reject(()->CreatePlayerProgress.validate(wrongStats),"invalid stats list silently became empty");
        after.getCompound("Hand").getCompound("Runtime").remove("Living");reject(()->CreateDeployerRollbackPlan.prepare(before,after),"new format accepted legacy partial runtime");
    }
    private static CompoundTag manifest(){
        var tag=new CompoundTag();tag.putInt("JointFormat",1);tag.putBoolean("Complete",true);tag.putString("Kind","deployer_action");tag.putUUID("Transfer",UUID.randomUUID());
        var hand=new CompoundTag();hand.putUUID("Construction",UUID.randomUUID());hand.putUUID("StorageEntity",UUID.randomUUID());hand.putString("ConstructionKind","MINECART");hand.putString("Dimension","minecraft:overworld");hand.putLong("LocalPos",0);
        var state=new CompoundTag();state.putString("Name","create:deployer");hand.put("State",state);hand.put("Settings",new CompoundTag());hand.putInt("Size",41);hand.putInt("Selected",0);
        var slots=new ListTag();var item=new CompoundTag();item.putInt("Slot",0);item.putString("id","minecraft:stone");item.putInt("count",2);slots.add(item);hand.put("Inventory",slots);
        var rt=new CompoundTag();rt.putBoolean("PlacedTracks",false);hand.put("Runtime",rt);tag.put("Hand",hand);
        for(String key:List.of("Cargo","World","Targets"))tag.put(key,new ListTag());return tag;
    }
    private static CompoundTag runtime(){
        var tag=new CompoundTag();tag.putInt("Format",1);tag.putInt("Tick",100);
        for(String key:List.of("AttackStrength","NoAction","HurtTime","HurtDuration","DeathTime","Invulnerable","LastMobAt","LastVictimAt","LastPlayerTime","UseRemaining"))tag.putInt(key,0);
        tag.putInt("AttackStrength",5);tag.putInt("NoAction",3);tag.putInt("HurtTime",8);tag.putInt("Invulnerable",20);tag.putInt("LastPlayerTime",8);
        tag.putFloat("LastHurt",0);tag.putLong("LastStamp",0);tag.putByte("Flags",(byte)0);tag.putBoolean("Dead",false);
        for(String key:List.of("LastMob","LastVictim","LastPlayer","LastSource","UseItem"))tag.put(key,new CompoundTag());
        var ref=new CompoundTag();ref.putUUID("UUID",UUID.randomUUID());ref.putString("Dimension","minecraft:overworld");ref.putString("Type","minecraft:player");ref.putBoolean("Actor",true);tag.put("LastMob",ref);tag.put("LastPlayer",ref.copy());
        var tracker=new CompoundTag();for(String key:List.of("LastDamageTime","Start","End"))tracker.putInt(key,0);tracker.putBoolean("InCombat",false);tracker.putBoolean("TakingDamage",false);tracker.put("Entries",new ListTag());tag.put("Tracker",tracker);
        var persistent=new CompoundTag();persistent.putFloat("Health",8);persistent.putFloat("AbsorptionAmount",0);persistent.putShort("HurtTime",(short)8);persistent.put("attributes",new ListTag());tag.put("Persistent",persistent);tag.put("Attributes",new ListTag());return tag;
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void reject(Runnable task,String message){try{task.run();}catch(IllegalStateException|IllegalArgumentException expected){return;}throw new AssertionError(message);}
}

package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import java.util.*;

final class RecoveryFluidBalanceTest {
    private static final BlockPos A=new BlockPos(0,64,0),B=new BlockPos(32,64,0);
    private static final RecoveryFluidBalance.Limits LIMITS=new RecoveryFluidBalance.Limits(8000,32);
    static void run() {
        var full=tank(1000,1,1);var empty=tank(0,1,1);
        verify(List.of(input(A,full,empty),input(B,empty,full)));
        refused("resource_graph",()->verify(List.of(input(A,empty,full))));
        refused("resource_graph",()->verify(List.of(input(A,full,empty))));
        var lava=full.copy();lava.getCompound("TankContent").getCompound("Fluid").putString("id","minecraft:lava");
        refused("resource_graph",()->verify(List.of(input(A,full,lava))));
        var component=full.copy();var components=new CompoundTag();components.putString("minecraft:custom_name","different");component.getCompound("TankContent").getCompound("Fluid").put("components",components);
        refused("resource_graph",()->verify(List.of(input(A,full,component))));
        var tall=tank(1000,1,2);var child=part(A);var top=A.above();
        verify(List.of(input(A,tall,tank(0,1,2)),input(top,child,child),input(B,empty,full)));
        refused("multiblock_incomplete",()->verify(List.of(input(A,tall,tall))));
        refused("controller_outside",()->verify(List.of(input(top,child,child))));
        var hidden=child.copy();hidden.put("TankContent",full.get("TankContent").copy());
        refused("alias_conflict",()->verify(List.of(input(A,tall,tall),input(top,hidden,hidden))));
        var wrong=part(B);refused("controller_",()->verify(List.of(input(A,tall,tall),input(top,wrong,wrong))));
        // Changing the physical shape must not count the same controller's fluid twice.
        verify(List.of(input(A,full,tall),input(top,empty,child)));
        var negative=tank(0,1,1);negative.getCompound("TankContent").put("Fluid",fluid(-1));
        refused("invalid_fluid_stack",()->verify(List.of(input(A,negative,negative))));
        var malformed=full.copy();malformed.getCompound("TankContent").getCompound("Fluid").putDouble("amount",1000.5);
        refused("invalid_fluid_stack",()->verify(List.of(input(A,malformed,malformed))));
        var over=tank(8001,1,1);refused("capacity_exceeded",()->verify(List.of(input(A,over,over))));
        var pending=empty.copy();pending.putBoolean("Uninitialized",true);refused("connectivity_pending",()->verify(List.of(input(A,pending,pending))));
        var boiler=empty.copy();boiler.getCompound("Boiler").putInt("Engines",1);refused("boiler_resource_graph",()->verify(List.of(input(A,boiler,boiler))));
        var bad=empty.copy();bad.remove("Boiler");refused("snapshot_required",()->verify(List.of(input(A,bad,bad))));
        var creative=empty.copy();creative.putString("id","create:creative_fluid_tank");refused("creative_fluid_contract",()->verify(List.of(input(A,creative,creative))));
        refused("adapter_unavailable",()->RecoveryFluidBalance.verify(List.of(input(A,full,full)),RecoveryFluidBalance.Limits.NONE,1_000_000,()->false));
        refused("лимит памяти",()->RecoveryFluidBalance.verify(List.of(input(A,full,full)),LIMITS,1,()->false));
        try{RecoveryFluidBalance.verify(List.of(input(A,full,full)),LIMITS,1_000_000,()->true);throw new AssertionError("cancel ignored");}catch(java.util.concurrent.CancellationException expected){}
        var copy=full.copy();verify(List.of(input(A,full,full)));check(copy.equals(full),"accounting mutated NBT");
        profile();System.out.println("RECOVERY_FLUID_BALANCE_OK: exact fluid/components, closed transfer, controller counted once, complete multiblock/shape changes, aliases, malformed/count/capacity/boiler/creative refusal, memory and cancellation");
    }
    static CompoundTag fluid(int amount){var fluid=new CompoundTag();fluid.putString("id","minecraft:water");fluid.putInt("amount",amount);return fluid;}
    static CompoundTag tank(int amount,int width,int height) {
        var tag=new CompoundTag();tag.putString("id","create:fluid_tank");tag.putInt("Size",width);tag.putInt("Height",height);tag.putBoolean("Window",true);
        var boiler=new CompoundTag();boiler.putFloat("Supply",0);boiler.putInt("ActiveHeat",0);boiler.putBoolean("PassiveHeat",false);boiler.putInt("Engines",0);boiler.putInt("Whistles",0);boiler.putBoolean("Update",false);tag.put("Boiler",boiler);
        var content=new CompoundTag();if(amount>0)content.put("Fluid",fluid(amount));tag.put("TankContent",content);return tag;
    }
    private static CompoundTag part(BlockPos root){var tag=tank(0,1,1);tag.remove("TankContent");tag.remove("Size");tag.remove("Height");tag.putIntArray("Controller",new int[]{root.getX(),root.getY(),root.getZ()});return tag;}
    private static RecoveryItemBalance.Input input(BlockPos pos,CompoundTag before,CompoundTag after){return new RecoveryItemBalance.Input("minecraft:overworld",pos,checkpoint(before),checkpoint(after));}
    private static RollbackEngine.ReplayCheckpoint checkpoint(CompoundTag be){var state=new CompoundTag();state.putString("Name","create:fluid_tank");return new RollbackEngine.ReplayCheckpoint(state,be,null);}
    private static void verify(List<RecoveryItemBalance.Input> inputs){RecoveryFluidBalance.verify(inputs,LIMITS,16*1024*1024,()->false);}
    private static void profile() {
        var inputs=new ArrayList<RecoveryItemBalance.Input>();for(int i=0;i<2048;i++)inputs.add(input(new BlockPos(i*4,64,0),tank(1000,1,1),tank(1000,1,1)));
        for(int i=0;i<3;i++)verify(inputs);long began=System.nanoTime();for(int i=0;i<5;i++)verify(inputs);
        System.out.printf(Locale.ROOT,"RECOVERY_FLUID_BALANCE_PROFILE tanks=2048 passes=5 average_ms=%.3f scope=detached_worker_only%n",(System.nanoTime()-began)/5e6);
    }
    private static void refused(String reason,Runnable run){try{run.run();throw new AssertionError("accepted: "+reason);}catch(IllegalStateException expected){check(expected.getMessage().contains(reason),"wrong refusal "+expected);}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

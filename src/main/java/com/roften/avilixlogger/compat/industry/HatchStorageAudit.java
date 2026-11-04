package com.roften.avilixlogger.compat.industry;

import com.roften.avilixlogger.compat.machines.MachineAudit;
import com.roften.avilixlogger.compat.teams.ModStateAudit;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Captures only the handler and block already selected by the hatch's native operation. */
public final class HatchStorageAudit {
    private static final ThreadLocal<Action> ACTIVE=new ThreadLocal<>();
    private HatchStorageAudit() {}
    public static Action begin(ServerPlayer player,BlockPos hatch) {
        if(!ModStateAudit.enabled(player.server,ActionType.EXPERIENCE_CHANGE))return null;
        var action=new Action(player,hatch,ACTIVE.get());ACTIVE.set(action);return action;
    }
    public static void bind(IFluidHandler handler,BlockEntity target) {
        var action=ACTIVE.get();if(action==null||action.handler!=null)return;
        try {
            if(target.getLevel()!=action.player.level()||target.isRemoved())return;
            action.location=MachineAudit.location(target);
            action.location.putString("PositionMeaning","native-capability-target");
            action.before=read(handler,target);action.handler=handler;action.target=target;
        } catch(RuntimeException|LinkageError failure){IndustrialAudit.fail("hatch-before");}
    }
    public static void transferred(FluidStack fluid,long amount,boolean insert) {
        var action=ACTIVE.get();if(action==null||action.handler==null||amount<=0||fluid.isEmpty())return;
        action.fluid=fluid.copyWithAmount(1);action.amount+=amount;action.insert=insert;
    }
    private static CompoundTag read(IFluidHandler handler,BlockEntity target) {
        int count=handler.getTanks();if(count<0||count>64)throw new IllegalStateException("Hatch tank budget");
        var tag=new CompoundTag();var tanks=new ListTag();
        for(int i=0;i<count;i++) {
            var tank=new CompoundTag();tank.putInt("Tank",i);tank.putInt("Capacity",handler.getTankCapacity(i));
            var fluid=handler.getFluidInTank(i);tank.putLong("AmountMB",fluid.getAmount());
            tank.put("Fluid",fluid.isEmpty()?new CompoundTag():fluid.save(target.getLevel().registryAccess(),new CompoundTag()));tanks.add(tank);
        }
        tag.put("Tanks",tanks);return tag;
    }
    private static CompoundTag experience(ServerPlayer player) {
        var tag=new CompoundTag();tag.putInt("Level",player.experienceLevel);tag.putFloat("Progress",player.experienceProgress);
        tag.putInt("TotalExperienceField",player.totalExperience);
        // Same level/progress meaning as CEI 2.4.2, using long to keep evidence arithmetic from wrapping.
        long level=player.experienceLevel;
        long base=level>=31?(9*level*level-325*level)/2+2220:level>=16?(5*level*level-91*level)/2+360:level*level+6*level;
        long next=level>=30?9*level-158:level>=15?5*level-38:2*level+7;
        tag.putLong("Points",base+Math.round(player.experienceProgress*(float)next));return tag;
    }
    public static final class Action {
        private final ServerPlayer player;private final BlockPos hatch;private final Action parent;
        private final CompoundTag playerBefore;
        private IFluidHandler handler;private BlockEntity target;private CompoundTag location,before;
        private FluidStack fluid=FluidStack.EMPTY;private long amount;private boolean insert,done;
        private Action(ServerPlayer player,BlockPos hatch,Action parent){this.player=player;this.hatch=hatch.immutable();this.parent=parent;playerBefore=experience(player);}
        public void finish(boolean threw) {
            if(done)return;done=true;
            try {
                if(handler==null||target==null)return;
                var after=read(handler,target);var playerAfter=experience(player);
                if(before.equals(after)&&playerBefore.equals(playerAfter)&&amount==0)return;
                var first=location.copy();first.put("StorageContents",before);first.put("PlayerExperience",playerBefore);
                var last=location.copy();last.put("StorageContents",after);last.put("PlayerExperience",playerAfter);
                first.putUUID("PlayerUUID",player.getUUID());last.putUUID("PlayerUUID",player.getUUID());
                var operation=new CompoundTag();operation.putInt("Version",1);operation.putString("Method","experience-hatch");
                operation.putString("Direction",amount>0?(insert?"insert":"extract"):"observed-partial-change");
                operation.putLong("TransferredMB",amount);operation.putLong("PlayerExperienceDelta",playerAfter.getLong("Points")-playerBefore.getLong("Points"));
                operation.putInt("HatchX",hatch.getX());operation.putInt("HatchY",hatch.getY());operation.putInt("HatchZ",hatch.getZ());
                if(!fluid.isEmpty())operation.put("FluidKey",fluid.save(player.registryAccess(),new CompoundTag()));
                last.put("StorageOperation",operation);last.putBoolean("NativeThrew",threw);
                ModStateAudit.receipt(player.server,"create_enchantment_industry","experience-hatch",ActionType.EXPERIENCE_CHANGE,first,last);
            } catch(RuntimeException|LinkageError failure){IndustrialAudit.fail("hatch-after");}
            finally {handler=null;target=null;before=null;location=null;if(parent==null)ACTIVE.remove();else ACTIVE.set(parent);}
        }
    }
}

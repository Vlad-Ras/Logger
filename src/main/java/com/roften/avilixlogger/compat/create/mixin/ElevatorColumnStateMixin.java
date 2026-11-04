package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.compat.create.*;
import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.*;

@Mixin(value=ElevatorColumn.class,remap=false)
public abstract class ElevatorColumnStateMixin implements CreateElevatorColumnAccess {
    @Shadow protected LevelAccessor level;
    @Shadow protected ElevatorColumn.ColumnCoords coords;
    @Shadow protected List<Integer> contacts;
    @Shadow protected int targetedYLevel;
    @Shadow protected boolean isActive;
    @Shadow protected boolean targetAvailable;
    @Shadow public int namesListVersion;
    public CompoundTag avilixlogger$column(){var tag=new CompoundTag();tag.putIntArray("Contacts",contacts);tag.putInt("TargetY",targetedYLevel);tag.putBoolean("Active",isActive);tag.putBoolean("TargetAvailable",targetAvailable);tag.putInt("NamesVersion",namesListVersion);return tag;}
    public void avilixlogger$column(CompoundTag tag){contacts=new ArrayList<>();for(int y:tag.getIntArray("Contacts"))contacts.add(y);targetedYLevel=tag.getInt("TargetY");isActive=tag.getBoolean("Active");targetAvailable=tag.getBoolean("TargetAvailable");namesListVersion=tag.getInt("NamesVersion");}
    @Inject(method={"setActive","target","add","remove","namesChanged","gatherAll","initNames","floorReached"},at=@At("HEAD"),cancellable=true)
    private void avilixlogger$locked(CallbackInfo ci){if(CreateElevatorRegistry.locked(level,coords))ci.cancel();}
    @Inject(method="getOrCreate",at=@At("HEAD"),cancellable=true)
    private static void avilixlogger$registered(LevelAccessor level,ElevatorColumn.ColumnCoords coords,CallbackInfoReturnable<ElevatorColumn> cir){
        if(CreateElevatorRegistry.locked(level,coords)){var column=ElevatorColumn.get(level,coords);cir.setReturnValue(column==null?new ElevatorColumn(level,coords):column);}
    }
}

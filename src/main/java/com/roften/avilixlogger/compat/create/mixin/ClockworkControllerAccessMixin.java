package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateControllerAccess;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import org.spongepowered.asm.mixin.*;
import java.util.*;
@Mixin(value=ClockworkBearingBlockEntity.class,remap=false)
public abstract class ClockworkControllerAccessMixin implements CreateControllerAccess {
    @Shadow protected ControlledContraptionEntity hourHand;
    @Shadow protected ControlledContraptionEntity minuteHand;
    @Shadow protected boolean assembleNextTick;
    public List<ControlledContraptionEntity> avilixlogger$controlled(){var result=new ArrayList<ControlledContraptionEntity>(2);if(hourHand!=null)result.add(hourHand);if(minuteHand!=null)result.add(minuteHand);return List.copyOf(result);}
    public void avilixlogger$clearControlled(){hourHand=minuteHand=null;}
    public net.minecraft.nbt.CompoundTag avilixlogger$runtime(){var tag=new net.minecraft.nbt.CompoundTag();tag.putBoolean("AssembleNextTick",assembleNextTick);return tag;}
    public void avilixlogger$runtime(net.minecraft.nbt.CompoundTag tag){assembleNextTick=tag.getBoolean("AssembleNextTick");}
}

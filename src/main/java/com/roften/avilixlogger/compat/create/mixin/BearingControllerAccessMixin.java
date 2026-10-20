package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateControllerAccess;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import org.spongepowered.asm.mixin.*;
import java.util.List;
@Mixin(value=MechanicalBearingBlockEntity.class,remap=false)
public abstract class BearingControllerAccessMixin implements CreateControllerAccess {
    @Shadow protected ControlledContraptionEntity movedContraption;
    @Shadow protected boolean assembleNextTick;
    public List<ControlledContraptionEntity> avilixlogger$controlled(){return movedContraption==null?List.of():List.of(movedContraption);}
    public void avilixlogger$clearControlled(){movedContraption=null;}
    public net.minecraft.nbt.CompoundTag avilixlogger$runtime(){var tag=new net.minecraft.nbt.CompoundTag();tag.putBoolean("AssembleNextTick",assembleNextTick);return tag;}
    public void avilixlogger$runtime(net.minecraft.nbt.CompoundTag tag){assembleNextTick=tag.getBoolean("AssembleNextTick");}
}

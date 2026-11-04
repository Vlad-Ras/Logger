package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateControllerAccess;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import org.spongepowered.asm.mixin.*;
import java.util.List;
@Mixin(value=LinearActuatorBlockEntity.class,remap=false)
public abstract class LinearControllerAccessMixin implements CreateControllerAccess {
    @Shadow public com.simibubi.create.content.contraptions.AbstractContraptionEntity movedContraption;
    @Shadow public boolean assembleNextTick;
    @Shadow public boolean needsContraption;
    @Shadow public float offset;
    public List<ControlledContraptionEntity> avilixlogger$controlled(){return movedContraption instanceof ControlledContraptionEntity ce?List.of(ce):List.of();}
    public void avilixlogger$clearControlled(){movedContraption=null;}
    public net.minecraft.nbt.CompoundTag avilixlogger$runtime(){var tag=new net.minecraft.nbt.CompoundTag();tag.putBoolean("AssembleNextTick",assembleNextTick);tag.putBoolean("NeedsContraption",needsContraption);tag.putFloat("ExactOffset",offset);tag.putBoolean("Removing",((net.minecraft.world.level.block.entity.BlockEntity)(Object)this).isRemoved());return tag;}
    public void avilixlogger$runtime(net.minecraft.nbt.CompoundTag tag){assembleNextTick=tag.getBoolean("AssembleNextTick");needsContraption=tag.getBoolean("NeedsContraption");if(tag.contains("ExactOffset",5))offset=tag.getFloat("ExactOffset");}
}

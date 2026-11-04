package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateOrientationAccess;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.*;
@Mixin(value=OrientedContraptionEntity.class,remap=false)
public abstract class OrientedRuntimeMixin implements CreateOrientationAccess {
    @Shadow protected boolean forceAngle;
    public boolean avilixlogger$forceAngle(){return forceAngle;}
    public void avilixlogger$forceAngle(boolean value){forceAngle=value;}
    public CompoundTag avilixlogger$orientation(){var self=(OrientedContraptionEntity)(Object)this;var tag=new CompoundTag();tag.putBoolean("ForceAngle",forceAngle);tag.putFloat("Yaw",self.yaw);tag.putFloat("PrevYaw",self.prevYaw);tag.putFloat("TargetYaw",self.targetYaw);tag.putFloat("Pitch",self.pitch);tag.putFloat("PrevPitch",self.prevPitch);tag.putInt("NonDamageTicks",self.nonDamageTicks);return tag;}
    public void avilixlogger$orientation(CompoundTag tag){var self=(OrientedContraptionEntity)(Object)this;forceAngle=tag.getBoolean("ForceAngle");self.yaw=tag.getFloat("Yaw");self.prevYaw=tag.getFloat("PrevYaw");self.targetYaw=tag.getFloat("TargetYaw");self.pitch=tag.getFloat("Pitch");self.prevPitch=tag.getFloat("PrevPitch");self.nonDamageTicks=tag.getInt("NonDamageTicks");}
}

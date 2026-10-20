package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateKineticAccess;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.*;
@Mixin(value=KineticBlockEntity.class,remap=false)
public abstract class KineticSnapshotAccessMixin implements CreateKineticAccess {
    @Shadow protected boolean wasMoved;
    @Shadow public boolean networkDirty;
    @Shadow public boolean updateSpeed;
    @Shadow public int preventSpeedUpdate;
    @Shadow private int flickerTally;
    @Shadow private int validationCountdown;
    public net.minecraft.nbt.CompoundTag avilixlogger$kineticRuntime(){var tag=new net.minecraft.nbt.CompoundTag();tag.putBoolean("WasMoved",wasMoved);tag.putBoolean("NetworkDirty",networkDirty);tag.putBoolean("UpdateSpeed",updateSpeed);tag.putInt("PreventSpeedUpdate",preventSpeedUpdate);tag.putInt("FlickerTally",flickerTally);tag.putInt("ValidationCountdown",validationCountdown);if((Object)this instanceof com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity generator)tag.putBoolean("ReactivateSource",generator.reActivateSource);return tag;}
    public void avilixlogger$kineticRuntime(net.minecraft.nbt.CompoundTag tag){wasMoved=tag.getBoolean("WasMoved");networkDirty=tag.getBoolean("NetworkDirty");updateSpeed=tag.getBoolean("UpdateSpeed");preventSpeedUpdate=tag.getInt("PreventSpeedUpdate");flickerTally=tag.getInt("FlickerTally");validationCountdown=tag.getInt("ValidationCountdown");if((Object)this instanceof com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity generator)generator.reActivateSource=tag.getBoolean("ReactivateSource");}
    public void avilixlogger$prepareSnapshotLoad(){wasMoved=false;}
}

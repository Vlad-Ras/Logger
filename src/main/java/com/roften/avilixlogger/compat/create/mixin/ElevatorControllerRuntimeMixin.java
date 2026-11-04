package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateControllerRuntimeAccess;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.*;
@Mixin(value=ElevatorPulleyBlockEntity.class,remap=false)
public abstract class ElevatorControllerRuntimeMixin implements CreateControllerRuntimeAccess {
    @Shadow private float prevSpeed;
    @Shadow private boolean arrived;
    @Shadow private int clientOffsetTarget;
    @Shadow private boolean initialOffsetReceived;
    public CompoundTag avilixlogger$extraRuntime(){var tag=new CompoundTag();tag.putFloat("PreviousSpeed",prevSpeed);tag.putBoolean("Arrived",arrived);tag.putInt("ClientOffsetTarget",clientOffsetTarget);tag.putBoolean("InitialOffsetReceived",initialOffsetReceived);return tag;}
    public void avilixlogger$extraRuntime(CompoundTag tag){prevSpeed=tag.getFloat("PreviousSpeed");arrived=tag.getBoolean("Arrived");clientOffsetTarget=tag.getInt("ClientOffsetTarget");initialOffsetReceived=tag.getBoolean("InitialOffsetReceived");}
}

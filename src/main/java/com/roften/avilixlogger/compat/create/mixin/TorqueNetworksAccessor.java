package com.roften.avilixlogger.compat.create.mixin;
import com.simibubi.create.content.kinetics.*;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;
@Mixin(value=TorquePropagator.class,remap=false)
public interface TorqueNetworksAccessor {
    @Accessor("networks") static Map<LevelAccessor,Map<Long,KineticNetwork>> avilixlogger$networks(){throw new AssertionError();}
}

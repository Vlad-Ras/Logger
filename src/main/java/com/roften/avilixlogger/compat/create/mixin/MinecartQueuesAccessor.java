package com.roften.avilixlogger.compat.create.mixin;
import com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController;
import net.createmod.catnip.data.WorldAttached;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.*;
@Mixin(value=CapabilityMinecartController.class,remap=false)
public interface MinecartQueuesAccessor {
    @Accessor("queuedAdditions") static WorldAttached<List<AbstractMinecart>> avilixlogger$additions(){throw new AssertionError();}
    @Accessor("queuedUnloads") static WorldAttached<List<UUID>> avilixlogger$unloads(){throw new AssertionError();}
}

package com.roften.avilixlogger.compat.create.mixin;
import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=SidedInvWrapper.class,remap=false)
public interface SidedContainerAccessor {
    @Accessor("inv") WorldlyContainer avilixlogger$container();
    @Accessor("side") Direction avilixlogger$side();
}

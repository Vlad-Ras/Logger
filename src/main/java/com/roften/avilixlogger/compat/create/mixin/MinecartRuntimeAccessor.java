package com.roften.avilixlogger.compat.create.mixin;
import com.simibubi.create.content.contraptions.minecart.capability.MinecartController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=MinecartController.class,remap=false)
public interface MinecartRuntimeAccessor {
    @Accessor("needsEntryRefresh") boolean avilixlogger$refresh();
    @Accessor("needsEntryRefresh") void avilixlogger$refresh(boolean value);
}

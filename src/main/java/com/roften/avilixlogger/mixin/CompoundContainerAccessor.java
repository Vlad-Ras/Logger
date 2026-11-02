package com.roften.avilixlogger.mixin;
import com.roften.avilixlogger.core.CompoundContainerAccess;
import net.minecraft.world.Container;
import net.minecraft.world.CompoundContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(CompoundContainer.class)
public interface CompoundContainerAccessor extends CompoundContainerAccess {
    @Accessor("container1") Container avilixlogger$first();
    @Accessor("container2") Container avilixlogger$second();
}

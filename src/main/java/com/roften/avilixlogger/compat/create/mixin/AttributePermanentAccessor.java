package com.roften.avilixlogger.compat.create.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;

@Mixin(AttributeInstance.class)
public interface AttributePermanentAccessor {
    @Accessor("permanentModifiers") Map<ResourceLocation,AttributeModifier> avilixlogger$permanent();
}

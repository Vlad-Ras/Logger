package com.roften.avilixlogger.compat.create.mixin;

import com.google.common.collect.Multimap;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.*;

@Mixin(value = Contraption.class, remap = false)
public interface CartContraptionAccessor {
    @Accessor("updateTags") Map<BlockPos, CompoundTag> avilixlogger$updateTags();
    @Accessor("superglue") List<AABB> avilixlogger$superglue();
    @Accessor("capturedMultiblocks") Multimap<BlockPos, StructureBlockInfo> avilixlogger$multiblocks();
}

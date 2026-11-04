package com.roften.avilixlogger.compat.create;
import com.simibubi.create.content.trains.graph.*;
import net.minecraft.nbt.CompoundTag;
public interface CreateNavigationAccess {
    CompoundTag avilixlogger$navigation(DimensionPalette palette);
    void avilixlogger$navigation(CompoundTag tag,TrackGraph graph,DimensionPalette palette);
}

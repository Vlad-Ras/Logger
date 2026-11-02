package com.roften.avilixlogger.compat.accessories;

import net.minecraft.nbt.CompoundTag;
import java.util.HashMap;
import java.util.Map;

/** Per native inventory, only changed slots pending the mod's existing observation. */
public interface AccessoriesInventoryState {
    Map<Integer, CompoundTag> avilixlogger$acknowledged();
}

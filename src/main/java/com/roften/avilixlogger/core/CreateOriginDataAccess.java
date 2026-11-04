package com.roften.avilixlogger.core;
import net.minecraft.nbt.CompoundTag;
/** Read existing NeoForge data without allocating an empty tag for ordinary item ticks. */
public interface CreateOriginDataAccess { CompoundTag avilixlogger$originData(); }

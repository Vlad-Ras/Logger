package com.roften.avilixlogger.compat.create;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
/** Exact native controller references; no world search or reflection at runtime. */
public interface CreateControllerAccess {
    List<ControlledContraptionEntity> avilixlogger$controlled();
    void avilixlogger$clearControlled();
    CompoundTag avilixlogger$runtime();
    void avilixlogger$runtime(CompoundTag tag);
}

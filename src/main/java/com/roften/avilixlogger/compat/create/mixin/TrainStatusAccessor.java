package com.roften.avilixlogger.compat.create.mixin;
import com.simibubi.create.content.trains.entity.TrainStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;
@Mixin(value=TrainStatus.class,remap=false)
public interface TrainStatusAccessor {
    @Accessor("queued") List<TrainStatus.StatusMessage> avilixlogger$queued();
}

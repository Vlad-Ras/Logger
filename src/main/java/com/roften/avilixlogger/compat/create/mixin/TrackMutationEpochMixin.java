package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateTrackEpoch;
import com.simibubi.create.content.trains.graph.TrackGraph;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=TrackGraph.class,remap=false)
public abstract class TrackMutationEpochMixin implements CreateTrackEpoch {
    @Unique private long avilixlogger$epoch;
    public long avilixlogger$epoch(){return avilixlogger$epoch;}
    @Inject(method={"markDirty","addNode","loadNode","disconnectNodes","transferAll","transfer","setId","setNetId"},at=@At("HEAD"))
    private void avilixlogger$changed(CallbackInfo ci){avilixlogger$epoch++;}
    @Inject(method={"addNodeIfAbsent","createNodeIfAbsent","removeNode","putConnection"},at=@At("HEAD"))
    private void avilixlogger$returned(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> ci){avilixlogger$epoch++;}
}

package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.ContraptionIdentity;
import com.simibubi.create.content.trains.entity.*;
import com.simibubi.create.content.trains.graph.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.UUID;
@Mixin(value=Carriage.class,remap=false)
public abstract class CarriageIdentityMixin implements ContraptionIdentity {
    @Unique private UUID avilixlogger$carriageId;
    public UUID avilixlogger$identity(){if(avilixlogger$carriageId==null)avilixlogger$carriageId=UUID.randomUUID();return avilixlogger$carriageId;}
    public void avilixlogger$identity(UUID id){avilixlogger$carriageId=id;}
    @Inject(method="write",at=@At("RETURN"),require=1)
    private void avilixlogger$save(DimensionPalette dimensions,HolderLookup.Provider registries,CallbackInfoReturnable<CompoundTag> ci){ci.getReturnValue().putUUID("AvilixCarriageId",avilixlogger$identity());}
    @Inject(method="read",at=@At("RETURN"),require=1)
    private static void avilixlogger$load(CompoundTag tag,HolderLookup.Provider registries,TrackGraph graph,DimensionPalette dimensions,CallbackInfoReturnable<Carriage> ci){
        if(tag.hasUUID("AvilixCarriageId"))((ContraptionIdentity)ci.getReturnValue()).avilixlogger$identity(tag.getUUID("AvilixCarriageId"));
    }
}

package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.ContraptionIdentity;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.UUID;
@Mixin(value=Contraption.class, remap=false)
public abstract class ContraptionIdentityMixin implements ContraptionIdentity {
    @Unique private UUID avilixlogger$logicalId;
    public UUID avilixlogger$identity() { return avilixlogger$logicalId; }
    public void avilixlogger$identity(UUID id) { avilixlogger$logicalId=id; }
    @Inject(method="writeNBT", at=@At("RETURN"), require=1)
    private void avilixlogger$save(HolderLookup.Provider registries, boolean packet, CallbackInfoReturnable<CompoundTag> ci) {
        if (avilixlogger$logicalId!=null) ci.getReturnValue().putUUID("AvilixContraptionId",avilixlogger$logicalId);
    }
    @Inject(method="readNBT", at=@At("RETURN"), require=1)
    private void avilixlogger$load(Level world, CompoundTag tag, boolean packet, CallbackInfo ci) {
        if(tag.hasUUID("AvilixContraptionId")) avilixlogger$logicalId=tag.getUUID("AvilixContraptionId");
    }
}

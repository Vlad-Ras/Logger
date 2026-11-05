package com.roften.avilixlogger.compat.dieselgenerators.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.*;
import com.roften.avilixlogger.compat.industry.*;

@Pseudo
@Mixin(targets = "com.jesz.createdieselgenerators.content.canister.CanisterBlockEntity", remap = false)
public abstract class CanisterMixin {
    @WrapMethod(method = "setCapacityEnchantLevel(I)V", remap = false)
    private void avilixlogger$capacity(int level, Operation<Void> original){
        if (!CaptureHandlers.DIESELGENERATORS_CANISTERMIXIN_CAPACITY.enabled()) { original.call(level); return; }
        var action=IndustrialAudit.begin(this,"canister-capacity",false); boolean success=false;
        try{original.call(level);success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "setComponentPatch(Lnet/minecraft/core/component/DataComponentPatch;)V", remap = false)
    private void avilixlogger$components(net.minecraft.core.component.DataComponentPatch patch, Operation<Void> original){
        if (!CaptureHandlers.DIESELGENERATORS_CANISTERMIXIN_COMPONENTS.enabled()) { original.call(patch); return; }
        var action=IndustrialAudit.begin(this,"canister-components",false); boolean success=false;
        try{original.call(patch);success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
}

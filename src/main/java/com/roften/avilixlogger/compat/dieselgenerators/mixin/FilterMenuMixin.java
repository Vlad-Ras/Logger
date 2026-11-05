package com.roften.avilixlogger.compat.dieselgenerators.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.industry.DieselAudit;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
@Pseudo
@Mixin(targets="com.jesz.createdieselgenerators.content.entity_filter.EntityFilterMenu",remap=false)
public abstract class FilterMenuMixin {
    @WrapMethod(method="saveData(Lnet/minecraft/world/item/ItemStack;)V",remap=false)
    private void avilixlogger$save(ItemStack filter,Operation<Void> original){
        if (!CaptureHandlers.DIESELGENERATORS_FILTERMENUMIXIN_SAVE.enabled()) { original.call(filter); return; }
        var action=DieselAudit.filterMenu(this);boolean success=false;
        try{original.call(filter);success=true;}finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method="clearContents()V",remap=false)
    private void avilixlogger$clear(Operation<Void> original){
        if (!CaptureHandlers.DIESELGENERATORS_FILTERMENUMIXIN_CLEAR.enabled()) { original.call(); return; }
        var action=DieselAudit.filterMenu(this);boolean success=false;
        try{original.call();success=true;}finally{if(action!=null)action.finish(!success);}
    }
}

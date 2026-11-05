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
@Mixin(targets = {"com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlockEntity","com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlockEntity","com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity"}, remap = false)
public abstract class EngineUpgradeMixin {
    @WrapMethod(method = "setUpgrade(Lcom/jesz/createdieselgenerators/content/diesel_engine/EngineUpgrades;)V", remap = false)
    private void avilixlogger$upgrade(@Coerce Object upgrade, Operation<Void> original){
        if (!CaptureHandlers.DIESELGENERATORS_ENGINEUPGRADEMIXIN_UPGRADE.enabled()) { original.call(upgrade); return; }
        var action=IndustrialAudit.begin(this,"engine-upgrade",false); boolean success=false;
        try{original.call(upgrade);success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
}

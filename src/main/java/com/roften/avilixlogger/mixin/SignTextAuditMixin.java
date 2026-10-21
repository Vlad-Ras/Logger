package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.CauseContext;
import com.roften.avilixlogger.core.SignTextAudit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import java.util.List;

@Mixin(SignBlockEntity.class)
public abstract class SignTextAuditMixin {
    @WrapMethod(method="updateSignText")
    private void avilixlogger$editor(Player player,boolean front,List<FilteredText> lines,Operation<Void> original){
        var sign=(SignBlockEntity)(Object)this;
        if(!(sign.getLevel() instanceof ServerLevel level)||!level.getServer().isSameThread()||!com.roften.avilixlogger.LoggerConfig.isEnabled()){original.call(player,front,lines);return;}
        try(var cause=CauseContext.push(player,CauseContext.Kind.USE_BLOCK,sign.getBlockPos(),null)){original.call(player,front,lines);}
    }
    @WrapMethod(method="setText")
    private boolean avilixlogger$text(SignText text,boolean front,Operation<Boolean> original){
        var sign=(SignBlockEntity)(Object)this;
        if(com.roften.avilixlogger.core.CartRestoreLocks.blockLocked(sign.getLevel(),sign.getBlockPos()))return false;
        if(sign.getText(front)==text)return original.call(text,front);
        var before=SignTextAudit.before(sign);
        boolean changed=original.call(text,front);
        if(changed)try{SignTextAudit.after(sign,before);}catch(RuntimeException failure){com.roften.avilixlogger.AvilixLoggerMod.LOGGER.error("[AvilixLogger] Sign edit capture failed",failure);}return changed;
    }
}

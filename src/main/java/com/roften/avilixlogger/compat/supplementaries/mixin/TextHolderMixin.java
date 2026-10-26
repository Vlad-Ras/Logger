package com.roften.avilixlogger.compat.supplementaries.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.supplementaries.SupplementariesTextAudit;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.*;
import java.util.List;

@Pseudo @Mixin(targets="net.mehvahdjukaar.supplementaries.common.block.TextHolder",remap=false)
public abstract class TextHolderMixin {
    @WrapMethod(method="acceptClientMessages(Lnet/minecraft/world/entity/player/Player;Ljava/util/List;)V")
    private void avilixlogger$acceptedText(Player player,List<?> messages,Operation<Void> original){SupplementariesTextAudit.beforeNativeText();original.call(player,messages);}
}

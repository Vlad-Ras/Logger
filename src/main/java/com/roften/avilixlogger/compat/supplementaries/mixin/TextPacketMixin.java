package com.roften.avilixlogger.compat.supplementaries.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.supplementaries.SupplementariesTextAudit;
import com.roften.avilixlogger.core.CartRestoreLocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.*;
import java.util.List;

@Pseudo @Mixin(targets="net.mehvahdjukaar.supplementaries.common.network.ServerBoundSetTextHolderPacket",remap=false)
public abstract class TextPacketMixin {
    @Shadow @Final private BlockPos pos;
    @WrapMethod(method="updateSignText(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/List;)V")
    private void avilixlogger$textPacket(ServerPlayer player,List<?> messages,Operation<Void> original){
        if(CartRestoreLocks.blockLocked(player.level(),pos))return;
        var scope=SupplementariesTextAudit.begin(player,pos);if(scope==null){original.call(player,messages);return;}
        boolean completed=false;try{original.call(player,messages);completed=true;}finally{scope.finish(completed);}
    }
}

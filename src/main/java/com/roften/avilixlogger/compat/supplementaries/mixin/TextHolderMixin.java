package com.roften.avilixlogger.compat.supplementaries.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.supplementaries.SupplementariesTextAudit;
import com.roften.avilixlogger.compat.supplementaries.TextHolderAuditAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.*;
import java.util.List;

@Pseudo @Mixin(targets="net.mehvahdjukaar.supplementaries.common.block.TextHolder",remap=false)
public abstract class TextHolderMixin implements TextHolderAuditAccess {
    @Shadow @Final private Component[] messages;
    @Shadow @Final private Component[] filteredMessages;
    @Shadow private boolean hasAntiqueInk;

    public boolean avilixlogger$matches(int firstLine,List<? extends Component> input){
        if(firstLine<0)return false;
        int count=Math.min(input.size(),Math.max(0,messages.length-firstLine));
        if(count==0)return true;
        // Native antique ink rewrites component fonts; conservatively snapshot this case.
        if(hasAntiqueInk)return false;
        for(int i=0;i<count;i++)if(!java.util.Objects.equals(messages[firstLine+i],input.get(i))
                ||!java.util.Objects.equals(filteredMessages[firstLine+i],input.get(i)))return false;
        return true;
    }
    @WrapMethod(method="acceptClientMessages(Lnet/minecraft/world/entity/player/Player;Ljava/util/List;)V")
    private void avilixlogger$acceptedText(Player player,List<?> messages,Operation<Void> original){SupplementariesTextAudit.beforeNativeText();original.call(player,messages);}
}

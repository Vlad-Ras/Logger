package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CartStorageAudit;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value=MountedItemStorage.class,remap=false)
public abstract class MountedStorageMenuAuditMixin {
    @WrapMethod(method="handleInteraction")
    private boolean avilixlogger$menu(ServerPlayer player,Contraption contraption,StructureBlockInfo info,Operation<Boolean> original){
        var ref=CartStorageAudit.ref(this);if(CartStorageAudit.locked(ref))return false;
        var previous=player.containerMenu;boolean result=original.call(player,contraption,info);
        if(result && ref!=null && player.containerMenu!=previous)CartStorageAudit.menu(player.containerMenu,ref);
        return result;
    }
}

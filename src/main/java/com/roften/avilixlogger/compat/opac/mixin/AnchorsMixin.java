package com.roften.avilixlogger.compat.opac.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.opac.OpenPacStateAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
@Pseudo
@Mixin(targets = "xaero.pac.common.claims.player.PlayerDimensionClaims", remap = false)
public abstract class AnchorsMixin {
    @WrapMethod(method = "addAnchor(Lnet/minecraft/world/level/ChunkPos;)Z", remap = false)
    private boolean avilixlogger$addAnchor(ChunkPos pos, Operation<Boolean> original) {
        var action = OpenPacStateAudit.anchor(this, pos, "addAnchor"); boolean success = false;
        try { var result = original.call(pos); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "removeAnchor(Lnet/minecraft/world/level/ChunkPos;)Z", remap = false)
    private boolean avilixlogger$removeAnchor(ChunkPos pos, Operation<Boolean> original) {
        var action = OpenPacStateAudit.anchor(this, pos, "removeAnchor"); boolean success = false;
        try { var result = original.call(pos); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
}

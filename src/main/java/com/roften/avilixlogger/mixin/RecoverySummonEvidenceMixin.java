package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.*;
import com.roften.avilixlogger.LoggerConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.commands.SummonCommand;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SummonCommand.class)
public abstract class RecoverySummonEvidenceMixin {
    @WrapMethod(method="createEntity")
    private static Entity avilixlogger$freeCreation(CommandSourceStack source,Holder.Reference<EntityType<?>> type,Vec3 pos,CompoundTag tag,boolean randomize,Operation<Entity> original) {
        if(!LoggerConfig.isCaptureEnabled()||!CaptureHandlers.ENTITY_SPAWN.enabled()||!CaptureTypePolicy.enabled(3))return original.call(source,type,pos,tag,randomize);
        try(var proof=EntityRecoveryEvidence.summon()){return original.call(source,type,pos,tag,randomize);}
    }
}

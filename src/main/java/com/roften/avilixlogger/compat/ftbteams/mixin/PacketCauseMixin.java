package com.roften.avilixlogger.compat.ftbteams.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ftbteams.FtbTeamsAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import com.mojang.authlib.GameProfile;
import java.util.*;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;
@Pseudo
@Mixin(targets = {
    "dev.ftb.mods.ftbteams.net.CreatePartyMessage",
    "dev.ftb.mods.ftbteams.net.PlayerGUIOperationMessage",
    "dev.ftb.mods.ftbteams.net.UpdatePropertiesRequestMessage",
    "dev.ftb.mods.ftbteams.net.SendMessageMessage",
    "dev.ftb.mods.ftbteams.net.ToggleChatRedirectionMessage",
    "dev.ftb.mods.ftbteams.net.OpenGUIMessage"
}, remap = false)
public abstract class PacketCauseMixin {
    @WrapOperation(method = "handle", at = @At(value = "INVOKE", target = "Ldev/architectury/networking/NetworkManager$PacketContext;queue(Ljava/lang/Runnable;)V"), remap = false)
    private static void avilixlogger$queue(@Coerce Object context, Runnable task, Operation<Void> original) {
        original.call(context, FtbTeamsAudit.packetTask(context, task));
    }
}

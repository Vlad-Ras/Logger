package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.net.GuiCommandOutput;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla sends system replies directly to the player entity, bypassing withSource(). */
@Mixin(CommandSourceStack.class)
public abstract class GuiCommandOutputMixin {
    @Shadow @Final private CommandSource source;
    @Shadow @Final private boolean silent;

    @Inject(method = "sendSystemMessage", at = @At("HEAD"), cancellable = true, require = 1)
    private void avilixlogger$guiReply(Component message, CallbackInfo callback) {
        if (source instanceof GuiCommandOutput) {
            if (!silent) source.sendSystemMessage(message);
            callback.cancel();
        }
    }
}

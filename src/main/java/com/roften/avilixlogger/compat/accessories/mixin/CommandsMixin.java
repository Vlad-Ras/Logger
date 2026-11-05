package com.roften.avilixlogger.compat.accessories.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.accessories.AccessoriesAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import com.mojang.brigadier.context.CommandContext;

@Pseudo
@Mixin(targets = "io.wispforest.accessories.commands.AccessoriesCommands", remap = false)
public abstract class CommandsMixin {
    @WrapMethod(method = "addModifier(Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/core/Holder;Lnet/minecraft/resources/ResourceLocation;DLnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;Ljava/lang/String;Z)I", remap = false)
    private static int avilixlogger$addModifier(CommandSourceStack source, LivingEntity entity, Holder<?> attribute, ResourceLocation id, double value, AttributeModifier.Operation operation, String slot, boolean stackable, Operation<Integer> original) {
        if (!CaptureHandlers.ACCESSORIES_COMMANDSMIXIN_ADDMODIFIER.enabled()) { return original.call(source, entity, attribute, id, value, operation, slot, stackable); }
        var action = AccessoriesAudit.hand(entity, "command-addModifier"); boolean success = false;
        try { int result = original.call(source, entity, attribute, id, value, operation, slot, stackable); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "removeModifier(Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/core/Holder;Lnet/minecraft/resources/ResourceLocation;)I", remap = false)
    private static int avilixlogger$removeModifier(CommandSourceStack source, LivingEntity entity, Holder<?> attribute, ResourceLocation id, Operation<Integer> original) {
        if (!CaptureHandlers.ACCESSORIES_COMMANDSMIXIN_REMOVEMODIFIER.enabled()) { return original.call(source, entity, attribute, id); }
        var action = AccessoriesAudit.hand(entity, "command-removeModifier"); boolean success = false;
        try { int result = original.call(source, entity, attribute, id); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "adjustSlotValidationOnStack(ILnet/minecraft/world/entity/LivingEntity;Lcom/mojang/brigadier/context/CommandContext;)I", remap = false)
    private static int avilixlogger$validate(int operation, LivingEntity entity, CommandContext<CommandSourceStack> context, Operation<Integer> original) {
        if (!CaptureHandlers.ACCESSORIES_COMMANDSMIXIN_VALIDATE.enabled()) { return original.call(operation, entity, context); }
        var action = AccessoriesAudit.hand(entity, "command-validate"); boolean success = false;
        try { int result = original.call(operation, entity, context); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "createRenderStack(Lcom/mojang/brigadier/context/CommandContext;)I", remap = false)
    private static int avilixlogger$create(CommandContext<CommandSourceStack> context, Operation<Integer> original) {
        if (!CaptureHandlers.ACCESSORIES_COMMANDSMIXIN_CREATE.enabled()) { return original.call(context); }
        var action = AccessoriesAudit.hand(context.getSource().getPlayer(), "command-create"); boolean success = false;
        try { int result = original.call(context); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
}

package com.roften.avilixlogger.compat.createcobblestone.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import com.roften.avilixlogger.compat.createcobblestone.CobblestoneAudit;
@Pseudo
@Mixin(targets = "net.createcobblestone.neoforge.blocks.MechanicalGeneratorBlockEntity", remap = false)
public abstract class GeneratorMixin {
    @WrapMethod(method = "removeItem(II)Lnet/minecraft/world/item/ItemStack;", remap = false)
    private ItemStack avilixlogger$remove(int slot, int count, Operation<ItemStack> original) {
        var action = CobblestoneAudit.begin(this, "remove"); boolean success = false;
        try { var result = original.call(slot, count); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "removeItemNoUpdate(I)Lnet/minecraft/world/item/ItemStack;", remap = false)
    private ItemStack avilixlogger$take(int slot, Operation<ItemStack> original) {
        var action = CobblestoneAudit.begin(this, "remove-no-update"); boolean success = false;
        try { var result = original.call(slot); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setItem(ILnet/minecraft/world/item/ItemStack;)V", remap = false)
    private void avilixlogger$set(int slot, ItemStack stack, Operation<Void> original) {
        var action = CobblestoneAudit.begin(this, "set"); boolean success = false;
        try { original.call(slot, stack); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "clearContent()V", remap = false)
    private void avilixlogger$clear(Operation<Void> original) {
        var action = CobblestoneAudit.begin(this, "clear"); boolean success = false;
        try { original.call(); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "changeType(Lnet/createcobblestone/neoforge/data/GeneratorType;)V", remap = false)
    private void avilixlogger$type(@Coerce Object type, Operation<Void> original) {
        var action = CobblestoneAudit.type(this); boolean success = false;
        try { original.call(type); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V", remap = false)
    private void avilixlogger$read(CompoundTag tag, HolderLookup.Provider provider, boolean clientPacket, Operation<Void> original) {
        var action = clientPacket ? null : CobblestoneAudit.begin(this, "live-nbt-read"); boolean success = false;
        try { original.call(tag, provider, clientPacket); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "tick()V", remap = false)
    private void avilixlogger$tick(Operation<Void> original) {
        try (var cause = com.roften.avilixlogger.core.CauseContext.unattributed()) {
            var action = CobblestoneAudit.tick(this); boolean success = false;
            try { original.call(); success = true; } finally { if (action != null) action.finish(!success); }
        }
    }

}

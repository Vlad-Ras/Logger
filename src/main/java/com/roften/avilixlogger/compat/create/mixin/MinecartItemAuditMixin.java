package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.mounted.MinecartContraptionItem;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MinecartContraptionItem.class, remap = false)
public abstract class MinecartItemAuditMixin {
    @WrapMethod(method = "useOn")
    private InteractionResult avilixlogger$use(UseOnContext context, Operation<InteractionResult> original) {
        try (var cause = CauseContext.push(context.getPlayer(), CauseContext.Kind.USE_ITEM, context.getClickedPos(), context.getItemInHand())) { return original.call(context); }
    }
    @WrapMethod(method = "addContraptionToMinecart")
    private static void avilixlogger$place(Level world, ItemStack stack, AbstractMinecart cart, Direction facing, Operation<Void> original) {
        if (!(world instanceof ServerLevel level) || !CreateCartAudit.enabled()) { original.call(world, stack, cart, facing); return; }
        original.call(world, stack, cart, facing);
        var e = CreateCartAudit.passenger(cart);
        if (e == null || level.getEntity(e.getUUID()) != e) return;
        var s = CreateCartAudit.state(e); var cause = CauseContext.peek();
        if (cause != null && cause.actorUuid() != null) { s.owner = cause.actorUuid(); s.ownerName = cause.actorName(); }
        s.sequence++; CreateCartAudit.attach(e);
        var holder = cause == null || cause.actorUuid() == null ? null : level.getPlayerByUUID(cause.actorUuid());
        CreateCartAudit.emit(e, ActionType.CART_PLACE, "place", CreateCartAudit.itemForm(e, stack.copyWithCount(1), holder), CreateCartAudit.snapshot(e, stack.get(AllDataComponents.MINECRAFT_CONTRAPTION_DATA)), "placed");
    }
    @WrapMethod(method = "wrenchCanBeUsedToPickUpMinecartContraptions")
    private static void avilixlogger$pack(PlayerInteractEvent.EntityInteract event, Operation<Void> original) {
        var e = CreateCartAudit.target(event.getTarget());
        if (e == null || !(event.getLevel() instanceof ServerLevel) || !CreateCartAudit.enabled() || !com.simibubi.create.AllItems.WRENCH.isIn(event.getItemStack())) { original.call(event); return; }
        var s = CreateCartAudit.state(e); if (s.locked) { event.setCanceled(true); return; }
        // Only metadata here. The full payload is reused from Create's successful item writer below.
        var capture = new CartPickupCapture(); capture.before = CreateCartAudit.snapshot(e, new CompoundTag());
        var previousCapture = CartPickupCapture.CURRENT.get(); CartPickupCapture.CURRENT.set(capture);
        s.sequence++; var previous = CartAuditContext.enter(s.stamp("pack")); s.removing = true;
        try (var cause = CauseContext.push(event.getEntity(), CauseContext.Kind.INTERACT_ENTITY, e.blockPosition(), event.getItemStack())) {
            original.call(event);
            if (e.isRemoved() && capture.packed != null) {
                CreateCartAudit.emit(e, ActionType.CART_PACK, "pack", capture.before, CreateCartAudit.itemForm(e, capture.packed, event.getEntity()), "packed; holder=" + event.getEntity().getName().getString());
            }
        } finally { s.removing = false; CartAuditContext.restore(previous); CartPickupCapture.CURRENT.set(previousCapture); }
    }
    @Inject(method = "create", at = @At("RETURN"), require = 1)
    private static void avilixlogger$packed(AbstractMinecart.Type type, OrientedContraptionEntity entity, CallbackInfoReturnable<ItemStack> cir) {
        var capture = CartPickupCapture.CURRENT.get();
        if (capture == null || cir.getReturnValue().isEmpty()) return;
        var tag = cir.getReturnValue().get(AllDataComponents.MINECRAFT_CONTRAPTION_DATA);
        if (tag == null) return;
        capture.before.getCompound("Entity").put("Contraption", tag.copy());
    }
    @WrapOperation(method = "wrenchCanBeUsedToPickUpMinecartContraptions", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;placeItemBackInInventory(Lnet/minecraft/world/item/ItemStack;)V"), require = 1)
    private static void avilixlogger$finalItem(net.minecraft.world.entity.player.Inventory inventory, ItemStack stack, Operation<Void> original) {
        var capture = CartPickupCapture.CURRENT.get();
        if (capture != null) capture.packed = stack.copy();
        original.call(inventory, stack);
    }
}

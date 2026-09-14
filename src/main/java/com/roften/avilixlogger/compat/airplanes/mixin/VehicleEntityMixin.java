package com.roften.avilixlogger.compat.airplanes.mixin;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.ActorTracker;
import com.roften.avilixlogger.core.CauseTracker;
import com.roften.avilixlogger.compat.airplanes.AirplanesCompatHooks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.UUID;

/**
 * Immersive Aircraft / Man of Many Planes compat:
 * - Persists owner tag to entity NBT and to dropped item tag.
 * - Remembers last player actor for break/remove and mount, so Logger can attribute events (no more "?").
 */
@Mixin(targets = "immersive_aircraft.entity.VehicleEntity")
public class VehicleEntityMixin {

    @Unique private Player avilixlogger$lastPlayer;
    @Unique private String avilixlogger$owner;
    @Unique private String avilixlogger$ownerName;
    @Unique private boolean avilixlogger$removalLogged;

    @Inject(method = "hurt", at = @At(value = "HEAD"), require = 0)
    private void avilixlogger$captureActor(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!LoggerConfig.isEnabled()) return;
        try {
            if (source == null) return;

            // 1) Capture a generic cause string (for crashes / mob damage / etc.)
            try {
                String msg = null;
                try { msg = source.getMsgId(); } catch (Throwable ignored2) {}
                if (msg == null) {
                    try {
                        Object t = source.type();
                        msg = (String) t.getClass().getMethod("msgId").invoke(t);
                    } catch (Throwable ignored2) {}
                }
                if (source.getEntity() != null) {
                    try {
                        msg = (msg == null ? "" : (msg + ":")) + source.getEntity().getType().toString();
                    } catch (Throwable ignored2) {}
                }
                if (msg != null && !msg.isBlank()) {
                    CauseTracker.note(((Entity)(Object)this).getUUID(), msg);
                }
            } catch (Throwable ignored2) {}

            // 2) Player actor attribution
            if (source.getEntity() instanceof Player p) {
                this.avilixlogger$lastPlayer = p;
                ActorTracker.note(((Entity)(Object)this).getUUID(), p.getUUID(), p.getName().getString());
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "hurt", at = @At("RETURN"), require = 0)
    private void avilixlogger$afterHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        String reason = this.avilixlogger$lastPlayer != null
                && this.avilixlogger$lastPlayer.getAbilities().instabuild ? "creative" : "damage";
        avilixlogger$logRemovalIfNeeded(reason);
    }

    // Immersive Aircraft 1.4.6: private void applyDamage(float amount, boolean force).
    // A RETURN injection avoids the invalid @Redirect receiver signature that prevented the
    // complete VehicleEntity mixin from loading on the dedicated server.
    @Inject(method = "applyDamage(FZ)V", at = @At("RETURN"), require = 0)
    private void avilixlogger$afterApplyDamage(float amount, boolean force, CallbackInfo ci) {
        avilixlogger$logRemovalIfNeeded("damage");
    }

    @Unique
    private void avilixlogger$logRemovalIfNeeded(String reason) {
        try {
            if (this.avilixlogger$removalLogged || !LoggerConfig.isEnabled()
                    || this.avilixlogger$lastPlayer == null) return;

            Entity vehicle = (Entity) (Object) this;
            if (!vehicle.isRemoved()) return;

            this.avilixlogger$removalLogged = true;
            ActorTracker.note(vehicle.getUUID(), this.avilixlogger$lastPlayer.getUUID(),
                    this.avilixlogger$lastPlayer.getName().getString());
            if (!vehicle.level().isClientSide
                    && vehicle.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                AirplanesCompatHooks.logRemoval(serverLevel, this.avilixlogger$lastPlayer,
                        vehicle, reason == null ? "damage" : reason);
            }
        } catch (Throwable ignored) {}
    }

    @Redirect(method = "interact", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;startRiding(Lnet/minecraft/world/entity/Entity;)Z"), require = 0)
    private boolean avilixlogger$noteMount(Player user, Entity entity) {
        boolean result = user.startRiding(entity);
        if (result && LoggerConfig.isEnabled()) {
            try {
                ActorTracker.note(entity.getUUID(), user.getUUID(), user.getName().getString());
                try {
                    if (!entity.level().isClientSide && entity.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                        AirplanesCompatHooks.logMount(sl, user, entity);
                    }
                } catch (Throwable ignored2) {}
            } catch (Throwable ignored) {}
        }
        return result;
    }

    @Inject(method = "readAdditionalSaveData", at = @At(value = "HEAD"), require = 0)
    private void avilixlogger$readOwnerFromEntityNbt(CompoundTag tag, CallbackInfo ci) {
        if (!LoggerConfig.isEnabled()) return;
        try {
            if (tag == null) return;
            if (tag.contains("owner_uuid")) {
                this.avilixlogger$owner = tag.getString("owner_uuid");
            } else if (tag.contains("owner")) {
                this.avilixlogger$owner = tag.getString("owner");
            }
            if (tag.contains("owner_name")) {
                this.avilixlogger$ownerName = tag.getString("owner_name");
            }
            if ((this.avilixlogger$ownerName == null || this.avilixlogger$ownerName.isBlank())
                    && this.avilixlogger$owner != null && !this.avilixlogger$owner.isBlank()) {
                // Backfill name hint from UUID if possible (helps with existing planes that only had UUID).
                try {
                    var server = ((Entity)(Object)this).getServer();
                    if (server != null) {
                        UUID u = UUID.fromString(this.avilixlogger$owner);
                        Object cache = server.getProfileCache();
                        if (cache != null) {
                            Object opt = cache.getClass().getMethod("get", UUID.class).invoke(cache, u);
                            if (opt != null && (boolean) opt.getClass().getMethod("isPresent").invoke(opt)) {
                                Object gp = opt.getClass().getMethod("get").invoke(opt);
                                String n = (String) gp.getClass().getMethod("getName").invoke(gp);
                                if (n != null && !n.isBlank()) this.avilixlogger$ownerName = n;
                            }
                        }
                    }
                } catch (Throwable ignored2) {}
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "readItemTag", at = @At(value = "HEAD"), require = 0)
    private void avilixlogger$readOwnerFromItemTag(ItemStack stack, CallbackInfo ci) {
        if (!LoggerConfig.isEnabled()) return;
        try {
            if (stack == null) return;
            if (stack.has(DataComponents.CUSTOM_DATA) && stack.get(DataComponents.CUSTOM_DATA) != null) {
                CompoundTag t = stack.get(DataComponents.CUSTOM_DATA).copyTag();
                if (t.contains("owner_uuid")) {
                    this.avilixlogger$owner = t.getString("owner_uuid");
                } else if (t.contains("owner")) {
                    this.avilixlogger$owner = t.getString("owner");
                }
                if (t.contains("owner_name")) {
                    this.avilixlogger$ownerName = t.getString("owner_name");
                }
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "addAdditionalSaveData", at = @At(value = "HEAD"), require = 0)
    private void avilixlogger$saveOwnerToEntityNbt(CompoundTag tag, CallbackInfo ci) {
        if (!LoggerConfig.isEnabled()) return;
        try {
            if (tag == null) return;
            if (this.avilixlogger$owner != null && !this.avilixlogger$owner.isBlank()) {
                tag.putString("owner", this.avilixlogger$owner);
                tag.putString("owner_uuid", this.avilixlogger$owner);
            }
            if (this.avilixlogger$ownerName != null && !this.avilixlogger$ownerName.isBlank()) {
                tag.putString("owner_name", this.avilixlogger$ownerName);
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "addItemTag", at = @At(value = "HEAD"), require = 0)
    private void avilixlogger$saveOwnerToItemTag(ItemStack stack, CallbackInfo ci) {
        if (!LoggerConfig.isEnabled()) return;
        try {
            if (stack == null) return;
            // Owner UUID is optional (admin can set only a name).
            if ((this.avilixlogger$owner == null || this.avilixlogger$owner.isBlank())
                    && (this.avilixlogger$ownerName == null || this.avilixlogger$ownerName.isBlank())) return;

            CompoundTag tag = new CompoundTag();
            if (stack.has(DataComponents.CUSTOM_DATA) && stack.get(DataComponents.CUSTOM_DATA) != null) {
                tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
            }
            if (this.avilixlogger$owner != null && !this.avilixlogger$owner.isBlank()) {
                tag.putString("owner", this.avilixlogger$owner);
                tag.putString("owner_uuid", this.avilixlogger$owner);
            }
            if (this.avilixlogger$ownerName != null && !this.avilixlogger$ownerName.isBlank()) {
                tag.putString("owner_name", this.avilixlogger$ownerName);
            }
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        } catch (Throwable ignored) {}
    }
}

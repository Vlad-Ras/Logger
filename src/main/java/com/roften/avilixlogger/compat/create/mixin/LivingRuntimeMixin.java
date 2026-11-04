package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.compat.create.*;
import net.minecraft.core.Holder;
import net.minecraft.nbt.*;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import java.util.*;

@Mixin(LivingEntity.class)
public abstract class LivingRuntimeMixin implements CreateLivingRuntimeAccess {
    @Shadow protected int attackStrengthTicker;
    @Shadow protected float lastHurt;
    @Shadow protected boolean dead;
    @Shadow protected int noActionTime;
    @Shadow private LivingEntity lastHurtByMob;
    @Shadow private int lastHurtByMobTimestamp;
    @Shadow private LivingEntity lastHurtMob;
    @Shadow private int lastHurtMobTimestamp;
    @Shadow protected Player lastHurtByPlayer;
    @Shadow protected int lastHurtByPlayerTime;
    @Shadow private DamageSource lastDamageSource;
    @Shadow private long lastDamageStamp;
    @Shadow protected ItemStack useItem;
    @Shadow protected int useItemRemaining;
    @Shadow @Final protected static EntityDataAccessor<Byte> DATA_LIVING_ENTITY_FLAGS;
    @Shadow @Final private Map<Holder<MobEffect>,MobEffectInstance> activeEffects;
    @Shadow private boolean effectsDirty;
    public CompoundTag avilixlogger$livingRuntime(LivingEntity actor){
        var self=(LivingEntity)(Object)this;var tag=new CompoundTag();tag.putInt("Format",1);tag.putInt("Tick",self.tickCount);
        tag.putInt("AttackStrength",attackStrengthTicker);tag.putFloat("LastHurt",lastHurt);tag.putBoolean("Dead",dead);tag.putInt("NoAction",noActionTime);
        tag.putInt("HurtTime",self.hurtTime);tag.putInt("HurtDuration",self.hurtDuration);tag.putInt("DeathTime",self.deathTime);tag.putInt("Invulnerable",self.invulnerableTime);
        tag.put("LastMob",CreateLivingRuntime.reference(lastHurtByMob,actor));tag.putInt("LastMobAt",lastHurtByMobTimestamp);
        tag.put("LastVictim",CreateLivingRuntime.reference(lastHurtMob,actor));tag.putInt("LastVictimAt",lastHurtMobTimestamp);
        tag.put("LastPlayer",CreateLivingRuntime.reference(lastHurtByPlayer,actor));tag.putInt("LastPlayerTime",lastHurtByPlayerTime);
        tag.put("LastSource",CreateLivingRuntime.damage(lastDamageSource,actor));tag.putLong("LastStamp",lastDamageStamp);
        if(useItem.isEmpty())tag.put("UseItem",new CompoundTag());
        else {
            // ItemStack#save uses the vanilla persistence codec (count <= 99).
            // The actor may be holding a modded or expanded stack.
            var saved=com.roften.avilixlogger.core.NbtSerde.snapshotItemStack(useItem,self.registryAccess());
            if(saved==null)throw new IllegalStateException("Активный предмет не сохранён полностью");
            tag.put("UseItem",saved);
        }
        tag.putInt("UseRemaining",useItemRemaining);
        tag.putByte("Flags",self.getEntityData().get(DATA_LIVING_ENTITY_FLAGS));
        tag.put("Tracker",((CreateCombatRuntimeAccess)self.getCombatTracker()).avilixlogger$combatRuntime(actor));return tag;
    }
    public void avilixlogger$livingRuntime(CompoundTag tag,LivingEntity actor){
        var self=(LivingEntity)(Object)this;
        var mob=CreateLivingRuntime.reference(tag.getCompound("LastMob"),actor);var victim=CreateLivingRuntime.reference(tag.getCompound("LastVictim"),actor);
        var player=CreateLivingRuntime.reference(tag.getCompound("LastPlayer"),actor);var source=CreateLivingRuntime.damage(tag.getCompound("LastSource"),actor);
        if(mob!=null&&!(mob instanceof LivingEntity)||victim!=null&&!(victim instanceof LivingEntity)||player!=null&&!(player instanceof Player))throw new IllegalStateException("Тип участника боя изменён");
        var item=CreateLivingRuntime.item(tag.getCompound("UseItem"),self);
        ((CreateCombatRuntimeAccess)self.getCombatTracker()).avilixlogger$combatRuntime(tag.getCompound("Tracker"),actor);
        attackStrengthTicker=tag.getInt("AttackStrength");lastHurt=tag.getFloat("LastHurt");dead=tag.getBoolean("Dead");noActionTime=tag.getInt("NoAction");
        self.hurtTime=tag.getInt("HurtTime");self.hurtDuration=tag.getInt("HurtDuration");self.deathTime=tag.getInt("DeathTime");self.invulnerableTime=tag.getInt("Invulnerable");
        lastHurtByMob=(LivingEntity)mob;lastHurtByMobTimestamp=tag.getInt("LastMobAt");lastHurtMob=(LivingEntity)victim;lastHurtMobTimestamp=tag.getInt("LastVictimAt");
        lastHurtByPlayer=(Player)player;lastHurtByPlayerTime=tag.getInt("LastPlayerTime");lastDamageSource=source;lastDamageStamp=tag.getLong("LastStamp");
        useItem=item;useItemRemaining=tag.getInt("UseRemaining");self.getEntityData().set(DATA_LIVING_ENTITY_FLAGS,tag.getByte("Flags"));
    }
    public void avilixlogger$clearEffectsForRestore(){activeEffects.clear();effectsDirty=true;}
}

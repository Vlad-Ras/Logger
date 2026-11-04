package com.roften.avilixlogger.compat.industry;

import com.roften.avilixlogger.compat.teams.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;

/** Observes impact effects without rewriting the mod's damage source or its permission checks. */
public final class IndustrialImpacts {
    private IndustrialImpacts() {}
    public static CompoundTag projectile(Entity value){
        var tag=new CompoundTag();
        if(!(value instanceof Projectile projectile)||!(projectile.level() instanceof ServerLevel level)||!level.getServer().isSameThread())return tag;
        try{
            tag.putUUID("ProjectileUUID",projectile.getUUID());tag.putString("Dimension",level.dimension().location().toString());
            tag.putDouble("ProjectileX",projectile.getX());tag.putDouble("ProjectileY",projectile.getY());tag.putDouble("ProjectileZ",projectile.getZ());
            tag.putFloat("Yaw",projectile.getYRot());tag.putFloat("Pitch",projectile.getXRot());
            var velocity=projectile.getDeltaMovement();tag.putDouble("VelocityX",velocity.x);tag.putDouble("VelocityY",velocity.y);tag.putDouble("VelocityZ",velocity.z);
            tag.put("Fire",StateAccess.encode(StateAccess.field(projectile,"fire")));tag.put("Cooling",StateAccess.encode(StateAccess.field(projectile,"cooling")));
            Object fluid=StateAccess.field(projectile,"stack");if(fluid instanceof net.neoforged.neoforge.fluids.FluidStack stack&&!stack.isEmpty())tag.put("Fluid",stack.save(level.registryAccess(),new CompoundTag()));
            if(projectile.getOwner()!=null)tag.putUUID("NativeOwnerUUID",projectile.getOwner().getUUID());
            var origin=IndustrialAudit.origin();if(origin!=null)tag.put("Mechanism",com.roften.avilixlogger.compat.machines.MachineAudit.location(origin));
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){tag.putBoolean("CaptureFailed",true);IndustrialAudit.fail("projectile-state");}
        return tag;
    }
    public static void spawned(Entity value,boolean accepted){
        if(!accepted||!(value instanceof IndustrialChemicalProjectile)||!(value instanceof Projectile projectile)||!(value.level() instanceof ServerLevel level)
                ||!ModStateAudit.enabled(level.getServer(),ActionType.PROJECTILE_SHOOT)||!com.roften.avilixlogger.LoggerConfig.VALUES.logProjectileShots.get())return;
        try(var cause=cause(value)){
            var before=new CompoundTag();var after=projectile(value);after.putBoolean("NativeSpawnAccepted",true);
            after.putInt("X",value.blockPosition().getX());after.putInt("Y",value.blockPosition().getY());after.putInt("Z",value.blockPosition().getZ());
            after.putUUID("EntityUUID",value.getUUID());after.putString("EntityType",net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(value.getType()).toString());after.putString("EntityName",value.getName().getString());
            ModStateAudit.receipt(level.getServer(),"createdieselgenerators","chemical-shot",ActionType.PROJECTILE_SHOOT,before,after);
        }catch(RuntimeException|LinkageError failure){IndustrialAudit.fail("projectile-spawn");}
    }
    public static CauseContext.Scope cause(Object value) {
        if(value instanceof Projectile projectile && projectile.level() instanceof ServerLevel level && level.getServer().isSameThread()
                && projectile.getOwner() instanceof Player player)
            return CauseContext.push(player,CauseContext.Kind.ATTACK_ENTITY,projectile.blockPosition(),net.minecraft.world.item.ItemStack.EMPTY);
        return CauseContext.unattributed();
    }
    public static ModStateAudit.Action hit(Object value,Entity target) {
        if(!(value instanceof Projectile projectile)||!(target.level() instanceof ServerLevel level)||!level.getServer().isSameThread())return null;
        return ModStateAudit.begin(level.getServer(),"createdieselgenerators","chemical-impact",target,"chemical-impact",ActionType.ENTITY_ATTACK,()->{
            var tag=new CompoundTag();var pos=target.blockPosition();
            tag.putString("Dimension",level.dimension().location().toString());tag.putInt("X",pos.getX());tag.putInt("Y",pos.getY());tag.putInt("Z",pos.getZ());
            tag.putUUID("EntityUUID",target.getUUID());tag.putString("EntityName",target.getName().getString());
            tag.putString("EntityType",net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
            tag.putUUID("ProjectileUUID",projectile.getUUID());tag.putBoolean("Alive",target.isAlive());tag.putInt("FireTicks",target.getRemainingFireTicks());
            if(projectile.getOwner()!=null)tag.putUUID("NativeOwnerUUID",projectile.getOwner().getUUID());
            tag.put("Fire",StateAccess.encode(StateAccess.field(projectile,"fire")));tag.put("Cooling",StateAccess.encode(StateAccess.field(projectile,"cooling")));
            Object fluid=StateAccess.field(projectile,"stack");
            if(fluid instanceof net.neoforged.neoforge.fluids.FluidStack stack&&!stack.isEmpty())tag.put("Fluid",stack.save(level.registryAccess(),new CompoundTag()));
            if(target instanceof LivingEntity living){
                tag.putFloat("Health",living.getHealth());tag.putFloat("Absorption",living.getAbsorptionAmount());
                var effects=new ListTag();if(living.getActiveEffects().size()>256)throw new IllegalStateException("Effects budget");
                var ops=level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
                for(var effect:living.getActiveEffects())effects.add(net.minecraft.world.effect.MobEffectInstance.CODEC.encodeStart(ops,effect).result().orElseThrow());
                tag.put("Effects",effects);
            }
            return tag;
        });
    }
}

package com.roften.avilixlogger.compat.create;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import net.minecraft.server.level.*;
import net.minecraft.nbt.*;
import java.util.*;
/** A connected recipient is restored in place; Entity.load is never used for a human. */
public final class CreateHumanRestore implements CreateTransferRollbackCoordinator.Endpoint {
    private final ServerPlayer player;private final ServerLevel level;private final DeployerFakePlayer actor;
    private final Set<String> ticking=new HashSet<>();
    private final CreateDeployerRollbackPlan.Undo undo;private final CreateLivingRuntime.Bindings bindings;
    public static CompoundTag capture(ServerPlayer player){
        var tag=new CompoundTag();tag.putInt("Format",1);tag.putInt("Menu",player.containerMenu.containerId);
        tag.put("Cursor",Objects.requireNonNullElseGet(NbtSerde.snapshotItemStack(player.containerMenu.getCarried(),player.registryAccess()),CompoundTag::new));
        tag.put("Progress",CreatePlayerProgress.capture(player));tag.put("Scores",CreateScoreRuntime.capture(player));tag.put("Cooldowns",((CreateCooldownRuntimeAccess)player.getCooldowns()).avilixlogger$cooldownRuntime());return tag;
    }
    public static void validate(CompoundTag tag){
        if(tag.getInt("Format")!=1||!tag.contains("Menu",3)||!tag.contains("Cursor",10))throw new IllegalStateException("Нет полного снимка получателя");
        CreatePlayerProgress.validate(tag.getCompound("Progress"));CreateScoreRuntime.validate(tag.getCompound("Scores"));CreateCooldownRuntimeAccess.validate(tag.getCompound("Cooldowns"));
    }
    public CreateHumanRestore(ServerLevel level,CreateDeployerRollbackPlan.Undo undo,DeployerFakePlayer actor){
        this.level=level;this.undo=undo;this.actor=actor;
        if(!(level.getEntity(undo.after().getUUID("Entity")) instanceof ServerPlayer human)||human instanceof net.neoforged.neoforge.common.util.FakePlayer||human.hasDisconnected())throw new IllegalStateException("Получатель вышел или заменён");player=human;
        bindings=CreateLivingRuntime.bind(actor,List.of(undo.before().getCompound("LivingRuntime"),undo.after().getCompound("LivingRuntime")));
        for(var state:List.of(undo.before(),undo.after())){
            var tag=state.getCompound("Human");validate(tag);bindings.run(()->CreateLivingRuntime.validateForRestore(player,actor,state.getCompound("LivingRuntime")));
            CreatePlayerProgress.preflight(player,tag.getCompound("Progress"));CreateScoreRuntime.preflight(player,tag.getCompound("Scores"));
            ((CreateCooldownRuntimeAccess)player.getCooldowns()).avilixlogger$validateCooldownRuntime(tag.getCompound("Cooldowns"));
            if(!tag.getCompound("Cursor").isEmpty())CreateLivingRuntime.item(tag.getCompound("Cursor"),player);
        }
        for(String key:List.of("Pos","Motion","Rotation","FallDistance","Fire","Air","OnGround","PortalCooldown","TicksFrozen"))if(Objects.equals(undo.before().getCompound("NBT").get(key),undo.after().getCompound("NBT").get(key)))ticking.add(key);
        ticking.add("HurtTime");
        verifyAfter();CartRestoreLocks.player(player);CartRestoreLocks.handler(player.getStats());CartRestoreLocks.handler(player.getAdvancements());CartRestoreLocks.keepLoaded(level,player.blockPosition());CartAuditContext.LOCKED_ENTITIES.add(player.getUUID());CreateScoreRuntime.reserve(player);
    }
    private CompoundTag stable(CompoundTag data){var tag=data.copy();CreateLivingRuntime.normalize(tag);for(String field:ticking)tag.remove(field);return tag;}
    private void verify(CompoundTag expected){
        if(player.isRemoved()||player.hasDisconnected()||player.level()!=level||level.getEntity(player.getUUID())!=player||!player.isAlive()||player.isPassenger()||!player.getPassengers().isEmpty()||!level.hasChunkAt(player.blockPosition()))throw new IllegalStateException("Получатель недоступен");
        if(!capture(player).equals(expected.getCompound("Human"))||!CreateLivingRuntime.matches(player,actor,expected.getCompound("LivingRuntime"))||!stable(CreateLivingRuntime.entitySnapshot(level,player)).equals(stable(expected.getCompound("NBT"))))throw new IllegalStateException("Инвентарь, питание, статистика или достижения получателя изменились");
    }
    private void set(CompoundTag state){
        var nbt=state.getCompound("NBT");
        ((com.roften.avilixlogger.compat.create.mixin.CreateOriginDataAccessor)player).avilixlogger$originData(nbt.contains("NeoForgeData",10)?nbt.getCompound("NeoForgeData").copy():null);
        if(!ticking.contains("Pos")||!ticking.contains("Rotation")){var pos=nbt.getList("Pos",6);var rot=nbt.getList("Rotation",5);player.teleportTo(level,ticking.contains("Pos")?player.getX():pos.getDouble(0),ticking.contains("Pos")?player.getY():pos.getDouble(1),ticking.contains("Pos")?player.getZ():pos.getDouble(2),ticking.contains("Rotation")?player.getYRot():rot.getFloat(0),ticking.contains("Rotation")?player.getXRot():rot.getFloat(1));}
        if(!ticking.contains("Motion")){var motion=nbt.getList("Motion",6);player.setDeltaMovement(motion.getDouble(0),motion.getDouble(1),motion.getDouble(2));}
        if(!ticking.contains("FallDistance"))player.fallDistance=nbt.getFloat("FallDistance");
        if(!ticking.contains("Fire"))player.setRemainingFireTicks(nbt.getShort("Fire"));
        if(!ticking.contains("Air"))player.setAirSupply(nbt.getShort("Air"));
        if(!ticking.contains("OnGround"))player.setOnGround(nbt.getBoolean("OnGround"));
        if(!ticking.contains("PortalCooldown"))player.setPortalCooldown(nbt.getInt("PortalCooldown"));
        if(!ticking.contains("TicksFrozen"))player.setTicksFrozen(nbt.getInt("TicksFrozen"));
        var tag=state.getCompound("Human");bindings.run(()->CreateLivingRuntime.restore(player,actor,state.getCompound("LivingRuntime")));
        CreatePlayerProgress.restore(player,tag.getCompound("Progress"));CreateScoreRuntime.restore(player,tag.getCompound("Scores"));
        ((CreateCooldownRuntimeAccess)player.getCooldowns()).avilixlogger$cooldownRuntime(tag.getCompound("Cooldowns"));player.containerMenu.setCarried(CreateLivingRuntime.item(tag.getCompound("Cursor"),player));
        player.getInventory().setChanged();player.inventoryMenu.broadcastFullState();if(player.containerMenu!=player.inventoryMenu)player.containerMenu.broadcastFullState();player.resetSentInfo();verify(state);
    }
    public void verifyAfter(){verify(undo.after());}public void verifyBefore(){verify(undo.before());}public void apply(){set(undo.before());}public void compensate(){set(undo.after());}
}

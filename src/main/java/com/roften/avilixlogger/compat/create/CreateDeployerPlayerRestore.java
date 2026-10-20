package com.roften.avilixlogger.compat.create;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
/** Restore player data only; never recreate/teleport a player from generic entity NBT. */
final class CreateDeployerPlayerRestore implements CreateTransferRollbackCoordinator.Endpoint {
    private final MinecraftServer server;private final ServerPlayer player;private final DeployerFakePlayer actor;
    private final CreateDeployerRollbackPlan.Undo undo;private final CreateLivingRuntime.Bindings bindings;
    private boolean departed,compensated;
    CreateDeployerPlayerRestore(MinecraftServer server,CreateDeployerRollbackPlan.Undo undo,DeployerFakePlayer actor){
        this.server=server;this.undo=undo;this.actor=actor;player=server.getPlayerList().getPlayer(undo.after().getUUID("Entity"));
        if(player==null||player instanceof net.neoforged.neoforge.common.util.FakePlayer)throw new IllegalStateException("Получатель пищи отсутствует");
        bindings=CreateLivingRuntime.bind(actor,List.of(undo.before().getCompound("LivingRuntime"),undo.after().getCompound("LivingRuntime")));
        for(var tag:List.of(undo.before(),undo.after())){CreatePlayerConsumption.preflight(player,tag.getCompound("PlayerState"));bindings.run(()->CreateLivingRuntime.validateForRestore(player,actor,tag.getCompound("LivingRuntime")));}
        verifyAfter();CartRestoreLocks.player(player);CreatePlayerConsumption.reserve(player);CartAuditContext.LOCKED_ENTITIES.add(player.getUUID());CartRestoreLocks.keepLoaded(player.serverLevel(),player.blockPosition());
    }
    private CompoundTag projected(CompoundTag original){var tag=original.copy();var cooldowns=tag.getCompound("Cooldowns");int now=((CreateCooldownRuntimeAccess)player.getCooldowns()).avilixlogger$cooldownRuntime().getInt("Tick");
        if(now<cooldowns.getInt("Tick"))throw new IllegalStateException("Получатель был перезагружен");cooldowns.putInt("Tick",now);cooldowns.getList("Entries",10).removeIf(raw->((CompoundTag)raw).getInt("End")<=now);return tag;}
    private void verify(CompoundTag tag){
        if(departed||server.getPlayerList().getPlayer(player.getUUID())!=player||!player.isAlive()||player.isRemoved()||player.isPassenger()||!player.getPassengers().isEmpty()
                ||!player.level().dimension().location().toString().equals(tag.getString("Dimension")))throw new IllegalStateException("Получатель вышел, умер или сменил измерение");
        if(!CreatePlayerConsumption.capture(player).equals(projected(tag.getCompound("PlayerState")))||!CreateLivingRuntime.matches(player,actor,tag.getCompound("LivingRuntime")))throw new IllegalStateException("Состояние получателя пищи изменилось");
    }
    private void set(CompoundTag tag){bindings.run(()->CreateLivingRuntime.restore(player,actor,tag.getCompound("LivingRuntime")));CreatePlayerConsumption.restore(player,projected(tag.getCompound("PlayerState")));verify(tag);}
    public void verifyAfter(){verify(undo.after());}public void verifyBefore(){verify(undo.before());}public void apply(){set(undo.before());}
    public void compensate(){if(!compensated)set(undo.after());}
    public void leaving(ServerPlayer leaving,boolean applied){if(leaving!=player)return;if(applied){set(undo.after());compensated=true;}departed=true;}
}

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
        for(String key:List.of("Pos","Motion","Rotation","FallDistance","Fire","Air","OnGround","PortalCooldown","TicksFrozen","warden_spawn_tracker"))if(Objects.equals(undo.before().getCompound("NBT").get(key),undo.after().getCompound("NBT").get(key)))ticking.add(key);
        ticking.add("HurtTime");ticking.add("recipeBook");
        verifyAfter();CartRestoreLocks.player(player);CartRestoreLocks.handler(player.getStats());CartRestoreLocks.handler(player.getAdvancements());CartRestoreLocks.keepLoaded(level,player.blockPosition());CartAuditContext.LOCKED_ENTITIES.add(player.getUUID());CreateScoreRuntime.reserve(player);
    }
    private CompoundTag stable(CompoundTag data){var tag=data.copy();CreateLivingRuntime.normalize(tag);for(String field:ticking)tag.remove(field);return tag;}
    private CompoundTag progressTarget(CompoundTag current,CompoundTag desired,boolean writing,boolean compensating){
        var other=desired==undo.before()?undo.after():undo.before();var from=(writing?other:desired).getCompound("Human");var to=(writing?desired:other).getCompound("Human");
        // During verification, merging to the opposite side merely checks every changed value.
        var out=current.copy();out.put("Progress",CreatePlayerProgress.merge(current.getCompound("Progress"),from.getCompound("Progress"),to.getCompound("Progress"),compensating));
        out.put("Scores",CreateScoreRuntime.merge(current.getCompound("Scores"),from.getCompound("Scores"),to.getCompound("Scores"),compensating));return out;
    }
    private static CompoundTag cooldown(CompoundTag saved,int tick){
        if(tick<saved.getInt("Tick"))throw new IllegalStateException("Получатель перезашёл после действия");var tag=saved.copy();tag.putInt("Tick",tick);tag.getList("Entries",10).removeIf(raw->((CompoundTag)raw).getInt("End")<=tick);return tag;
    }
    private void verify(CompoundTag expected){
        if(player.isRemoved()||player.hasDisconnected()||player.level()!=level||level.getEntity(player.getUUID())!=player||!player.isAlive()||player.isPassenger()||!player.getPassengers().isEmpty()||!level.hasChunkAt(player.blockPosition()))throw new IllegalStateException("Получатель недоступен");
        var live=capture(player);progressTarget(live,expected,false,false);var human=expected.getCompound("Human").copy();
        human.put("Progress",live.getCompound("Progress"));human.put("Scores",live.getCompound("Scores"));
        var b=undo.before().getCompound("Human").getCompound("Cooldowns");var a=undo.after().getCompound("Human").getCompound("Cooldowns");
        human.put("Cooldowns",b.equals(a)?live.getCompound("Cooldowns"):cooldown(human.getCompound("Cooldowns"),live.getCompound("Cooldowns").getInt("Tick")));
        if(!live.equals(human))throw new IllegalStateException("Состояние получателя изменилось: "+differences("Human",live,human));
        var runtime=CreateLivingRuntime.capture(player,actor);var wanted=CreateLivingRuntime.project(expected.getCompound("LivingRuntime"),player.tickCount);
        if(!runtime.equals(wanted))throw new IllegalStateException("Runtime получателя изменился: "+differences("Living",runtime,wanted));
        var actual=stable(CreateLivingRuntime.entitySnapshot(level,player));var nbt=stable(expected.getCompound("NBT"));if(!actual.equals(nbt))throw new IllegalStateException("NBT получателя изменился: "+differences("NBT",actual,nbt));
    }
    private static List<String> differences(String path,CompoundTag actual,CompoundTag expected){
        var out=new ArrayList<String>();var keys=new TreeSet<>(actual.getAllKeys());keys.addAll(expected.getAllKeys());
        for(String key:keys){if(Objects.equals(actual.get(key),expected.get(key)))continue;String name=path+"/"+key;
            if(actual.get(key) instanceof CompoundTag a&&expected.get(key) instanceof CompoundTag b)out.addAll(differences(name,a,b));else out.add(name+(actual.get(key) instanceof NumericTag?" ("+actual.get(key)+" / "+expected.get(key)+")":""));if(out.size()>=16)break;
        }return out;
    }
    private void set(CompoundTag state,boolean compensating){
        var current=capture(player);var merged=progressTarget(current,state,true,compensating);
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
        CreatePlayerProgress.restore(player,merged.getCompound("Progress"));CreateScoreRuntime.restore(player,merged.getCompound("Scores"));
        if(!undo.before().getCompound("Human").getCompound("Cooldowns").equals(undo.after().getCompound("Human").getCompound("Cooldowns")))((CreateCooldownRuntimeAccess)player.getCooldowns()).avilixlogger$cooldownRuntime(cooldown(tag.getCompound("Cooldowns"),current.getCompound("Cooldowns").getInt("Tick")));player.containerMenu.setCarried(CreateLivingRuntime.item(tag.getCompound("Cursor"),player));
        player.getInventory().setChanged();player.inventoryMenu.broadcastFullState();if(player.containerMenu!=player.inventoryMenu)player.containerMenu.broadcastFullState();player.resetSentInfo();verify(state);
    }
    public void verifyAfter(){verify(undo.after());}public void verifyBefore(){verify(undo.before());}public void apply(){set(undo.before(),false);}public void compensate(){set(undo.after(),true);}
}

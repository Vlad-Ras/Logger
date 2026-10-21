package com.roften.avilixlogger.compat.create;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import java.util.*;
/** Reserved sparse endpoints used by the existing compensated transfer coordinator. */
public final class CreateDeployerRestore {
    private static ServerLevel level(MinecraftServer server,CompoundTag tag){var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(tag.getString("Dimension"))));if(level==null)throw new IllegalStateException("Измерение активатора недоступно");return level;}
    public static CreateTransferRollbackCoordinator.Endpoint hand(MinecraftServer server,CreateDeployerRollbackPlan.Undo undo){return hand(server,undo,Map.of());}
    public static CreateTransferRollbackCoordinator.Endpoint hand(MinecraftServer server,CreateDeployerRollbackPlan.Undo undo,Map<UUID,Entity> candidates){return new Hand(server,undo,candidates);}
    public static Map<UUID,Entity> prepareTargets(MinecraftServer server,List<CreateDeployerRollbackPlan.Undo> targets){
        var values=new HashMap<UUID,Entity>();
        for(var undo:targets){var tag=undo.after();var world=level(server,tag);var id=tag.getUUID("Entity");var entity=world.getEntity(id);
            if(tag.getBoolean("Terminal")&&(entity==null||entity.isRemoved())){
                if(!world.hasChunkAt(BlockPos.of(tag.getLong("Pos"))))throw new IllegalStateException("Чанк умершей цели выгружен");
                entity=EntityType.loadEntityRecursive(undo.before().getCompound("NBT").copy(),world,e->e);
                if(!(entity instanceof LivingEntity)||!id.equals(entity.getUUID()))throw new IllegalStateException("Нет кандидата возврата умершей цели");
                entity.tickCount=Math.max(undo.before().getCompound("LivingRuntime").getInt("Tick"),tag.getCompound("Death").getCompound("LivingRuntime").getInt("Tick"));
            }
            if(entity!=null)values.put(id,entity);
        }
        return values;
    }
    public static CreateTransferRollbackCoordinator.Endpoint block(MinecraftServer server,CreateDeployerRollbackPlan.Undo undo){return new Block(server,undo);}
    public static CreateTransferRollbackCoordinator.Endpoint target(MinecraftServer server,CreateDeployerRollbackPlan.Undo undo,CompoundTag hand){return target(server,undo,hand,Map.of());}
    public static CreateTransferRollbackCoordinator.Endpoint target(MinecraftServer server,CreateDeployerRollbackPlan.Undo undo,CompoundTag hand,Map<UUID,Entity> candidates){
        var level=level(server,hand);var found=level.getEntity(hand.getUUID("StorageEntity"));
        if(!(found instanceof AbstractContraptionEntity body)||body.getContraption()==null)throw new IllegalStateException("Конструкция участника боя недоступна");
        var actor=body.getContraption().getActors().stream().map(pair->pair.getRight()).filter(Objects::nonNull)
                .filter(context->context.localPos.asLong()==hand.getLong("LocalPos")).map(context->context.temporaryData)
                .filter(value->value instanceof DeployerFakePlayer).map(value->(DeployerFakePlayer)value).findFirst().orElseThrow(()->new IllegalStateException("Технический игрок боя заменён"));
        if(undo.after().getString("Type").equals("minecraft:player"))return new CreateHumanRestore(level(server,undo.after()),undo,actor);
        return new Target(server,undo,actor,candidates);
    }
    private static final class Hand implements CreateTransferRollbackCoordinator.Endpoint {
        final AbstractContraptionEntity entity;final MovementContext context;final DeployerFakePlayer player;final CreateDeployerRollbackPlan.Undo undo;final CreateLivingRuntime.Bindings bindings;
        Hand(MinecraftServer server,CreateDeployerRollbackPlan.Undo undo,Map<UUID,Entity> candidates){this.undo=undo;var a=undo.after();var world=level(server,a);var found=world.getEntity(a.getUUID("StorageEntity"));
            if(!(found instanceof AbstractContraptionEntity body)||body.isRemoved()||body.getContraption()==null||!CreateContraptionSources.id(body).equals(a.getUUID("Construction"))||!CreateContraptionSources.kind(body).name().equals(a.getString("ConstructionKind")))throw new IllegalStateException("Конструкция активатора недоступна");entity=body;
            if(body instanceof com.simibubi.create.content.trains.entity.CarriageContraptionEntity carriage
                    &&(carriage.getCarriage()==null||carriage.getCarriage().presentInMultipleDimensions()))throw new IllegalStateException("Вагон активатора находится в портале или выгружен");
            context=body.getContraption().getActors().stream().map(pair->pair.getRight()).filter(Objects::nonNull).filter(c->c.localPos.asLong()==a.getLong("LocalPos")).findFirst().orElseThrow(()->new IllegalStateException("Нет нативного контекста активатора"));
            if(!(context.temporaryData instanceof DeployerFakePlayer fake))throw new IllegalStateException("Активатор выгружен или сменил технического игрока");player=fake;
            bindings=CreateLivingRuntime.bind(fake,List.of(undo.before().getCompound("Runtime").getCompound("Living"),undo.after().getCompound("Runtime").getCompound("Living")),candidates);
            for(var tag:List.of(undo.before(),undo.after())){var runtime=tag.getCompound("Runtime");
                if(runtime.contains("Progress",10))CreatePlayerProgress.preflight(fake,runtime.getCompound("Progress"));
                if(runtime.contains("Scores",10))CreateScoreRuntime.preflight(fake,runtime.getCompound("Scores"));
                if(runtime.contains("Living",10))bindings.run(()->CreateLivingRuntime.validateForRestore(fake,fake,runtime.getCompound("Living")));
                if(runtime.contains("Cooldowns",10))((CreateCooldownRuntimeAccess)fake.getCooldowns()).avilixlogger$validateCooldownRuntime(runtime.getCompound("Cooldowns"));}
            for(var tag:List.of(undo.before(),undo.after()))for(var raw:tag.getList("Inventory",10)){var slot=(CompoundTag)raw;var decoded=NbtSerde.readItemStack(slot,world.registryAccess());if(decoded.isEmpty()||decoded.getCount()!=slot.getInt("count"))throw new IllegalStateException("Предмет активатора не прочитан полностью");}
            verifyAfter();CartAuditContext.LOCKED_ENTITIES.add(body.getUUID());if(body.getVehicle()!=null)CartAuditContext.LOCKED_ENTITIES.add(body.getVehicle().getUUID());CartRestoreLocks.construction(a.getUUID("Construction"));CartRestoreLocks.player(fake);CartRestoreLocks.handler(fake.getStats());CartRestoreLocks.handler(fake.getAdvancements());if(a.getCompound("Runtime").contains("Scores",10))CreateScoreRuntime.reserve(fake);CartRestoreLocks.keepLoaded(world,body.blockPosition());CartStorageAudit.lockMenus(a.getUUID("Construction"),server);
        }
        void verify(CompoundTag expected){expected=expected.copy();CreateLivingRuntime.normalize(expected.getCompound("Runtime").getCompound("Living").getCompound("Persistent"));var actual=CreateDeployerTransaction.hand(context,player);
            if(!expected.getCompound("Runtime").contains("Progress")){actual.getCompound("Runtime").remove("Progress");actual.getCompound("Runtime").remove("Scores");}
            if(!expected.getCompound("Runtime").contains("Living")){actual.getCompound("Runtime").remove("Living");actual.getCompound("Runtime").remove("Cooldowns");}
            if(entity.isRemoved()||!entity.level().hasChunkAt(entity.blockPosition())||context.contraption!=entity.getContraption()||context.temporaryData!=player||entity.getContraption().getActors().stream().noneMatch(p->p.getRight()==context)||!actual.equals(expected))throw new IllegalStateException("Рука или настройки активатора изменились");}
        void set(CompoundTag tag){bindings.run(()->((CreateDeployerRuntimeAccess)player).avilixlogger$deployerRuntime(tag.getCompound("Runtime")));
            var inv=player.getInventory();inv.clearContent();inv.selected=tag.getInt("Selected");for(var raw:tag.getList("Inventory",10)){var slot=(CompoundTag)raw;inv.setItem(slot.getInt("Slot"),NbtSerde.readItemStack(slot,player.registryAccess()));}
            var runtime=tag.getCompound("Runtime");context.stall=runtime.contains("BreakingPos",4);context.data.remove("Timer");if(runtime.contains("Timer",3))context.data.putInt("Timer",runtime.getInt("Timer"));
            var held=NbtSerde.snapshotItemStack(player.getMainHandItem(),player.registryAccess());context.data.put("HeldItem",held==null?new CompoundTag():held);context.blockEntityData.remove("Inventory");verify(tag);}
        public void verifyAfter(){verify(undo.after());}public void verifyBefore(){verify(undo.before());}public void apply(){set(undo.before());}public void compensate(){set(undo.after());}
    }
    private static final class Block implements CreateTransferRollbackCoordinator.Endpoint {
        final ServerLevel level;final BlockPos pos;final CreateDeployerRollbackPlan.Undo undo;
        Block(MinecraftServer server,CreateDeployerRollbackPlan.Undo undo){this.undo=undo;level=level(server,undo.after());pos=BlockPos.of(undo.after().getLong("Pos"));verifyAfter();CartRestoreLocks.keepLoaded(level,pos);CartRestoreLocks.block(level,pos);for(var dir:Direction.values())CartRestoreLocks.block(level,pos.relative(dir));CartRollbackCoordinator.reserveHandlers(level,pos,true);}
        void verify(CompoundTag tag){if(!CreateStructuralAudit.world(level,pos).equals(tag))throw new IllegalStateException("Цель блока активатора изменилась: "+pos);}
        void set(CompoundTag tag){var state=NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK),tag.getCompound("State"));if(!NbtUtils.writeBlockState(state).equals(tag.getCompound("State")))throw new IllegalStateException("Состояние цели не прочитано полностью");CartRollbackCoordinator.apply(level,pos,state,tag.contains("BE",10)?tag.getCompound("BE"):null,tag.contains("Slots",10)?tag.getCompound("Slots"):null);verify(tag);}
        public void verifyAfter(){verify(undo.after());}public void verifyBefore(){verify(undo.before());}public void apply(){set(undo.before());}public void compensate(){set(undo.after());}
    }
    private static final class Target implements CreateTransferRollbackCoordinator.Endpoint {
        final MinecraftServer server;final ServerLevel level;final UUID id;final CreateDeployerRollbackPlan.Undo undo;final DeployerFakePlayer actor;final CreateLivingRuntime.Bindings bindings;final Entity candidate;final CompoundTag deathRuntime;final boolean corpse;Entity current;
        Target(MinecraftServer server,CreateDeployerRollbackPlan.Undo undo,DeployerFakePlayer actor,Map<UUID,Entity> candidates){this.server=server;this.undo=undo;this.actor=actor;level=level(server,undo.after());id=undo.after().getUUID("Entity");
            candidate=candidates.get(id);corpse=undo.after().getBoolean("Terminal")&&level.getEntity(id)!=null;
            deathRuntime=corpse&&level.getEntity(id) instanceof LivingEntity living?CreateLivingRuntime.capture(living,actor):null;
            bindings=CreateLivingRuntime.bind(actor,List.of(undo.before().getCompound("LivingRuntime"),undo.after().getCompound("LivingRuntime"),undo.after().getCompound("Death").getCompound("LivingRuntime")),candidates);
            if((candidate==null?level.getEntity(id):candidate) instanceof LivingEntity living)for(var tag:List.of(undo.before(),undo.after(),undo.after().getCompound("Death")))
                if(tag.contains("LivingRuntime",10))bindings.run(()->CreateLivingRuntime.validateForRestore(living,actor,tag.getCompound("LivingRuntime")));
            for(var tag:List.of(undo.before(),undo.after())){var pos=BlockPos.of(tag.getLong("Pos"));if(!level.hasChunkAt(pos))throw new IllegalStateException("Чанк цели сущности не загружен");CartRestoreLocks.keepLoaded(level,pos);}
            if(candidate!=null)for(var tag:List.of(undo.before(),undo.after().getBoolean("Terminal")?undo.after().getCompound("Death"):undo.after()))if(tag.contains("Scores",10))CreateScoreRuntime.preflight(candidate,tag.getCompound("Scores"));
            tickingFields();verifyAfter();if(candidate!=null&&undo.before().contains("Scores",10))CreateScoreRuntime.reserve(candidate);liveAfter=current==null?null:NbtSerde.snapshotEntity(level,current);CartAuditContext.LOCKED_ENTITIES.add(id);}
        // Natural ticking is allowed only for fields the activation did not change. Those
        // fields are preserved on a data-only undo; changed motion/fire/etc must still match.
        final Set<String> ticking=new HashSet<>();CompoundTag liveAfter;
        void tickingFields(){
            var b=undo.before();var a=undo.after();boolean both=b.getBoolean("Exists")&&a.getBoolean("Exists");
            var keys=new ArrayList<>(List.of("Pos","Motion","Rotation","FallDistance","Fire","Air","OnGround","PortalCooldown","TicksFrozen","PickupDelay"));
            if(a.getString("Type").equals("minecraft:item")||a.getString("Type").equals("minecraft:experience_orb"))keys.add("Age");
            for(String key:keys)if(!both||Objects.equals(b.getCompound("NBT").get(key),a.getCompound("NBT").get(key)))ticking.add(key);
            if(a.contains("LivingRuntime",10))ticking.add("HurtTime");
        }
        CompoundTag stable(CompoundTag nbt){var copy=nbt.copy();CreateLivingRuntime.normalize(copy);for(String key:ticking)copy.remove(key);return copy;}
        void verify(CompoundTag tag){for(var other:server.getAllLevels())if(other!=level&&other.getEntity(id)!=null)throw new IllegalStateException("Цель сменила измерение");current=level.getEntity(id);
            if(tag.getBoolean("Terminal")){verifyTerminal(tag);return;}
            if(!tag.getBoolean("Exists")){if(current!=null&&!current.isRemoved())throw new IllegalStateException("Удалённая цель снова появилась");return;}
            if(current==null||current.isRemoved()||current.isPassenger()||!current.getPassengers().isEmpty()||!level.hasChunkAt(current.blockPosition()))throw new IllegalStateException("Цель удалена, выгружена или связана с пассажиром");
            var actual=NbtSerde.snapshotEntity(level,current);if(actual==null||!stable(actual).equals(stable(tag.getCompound("NBT"))))throw new IllegalStateException("Данные цели сущности изменились"+(actual==null?"":": "+differences(stable(actual),stable(tag.getCompound("NBT")))));
            if(tag.contains("Scores",10)&&!CreateScoreRuntime.capture(current).equals(tag.getCompound("Scores")))throw new IllegalStateException("Scoreboard цели изменился");
            if(tag.contains("LivingRuntime",10)&&(!(current instanceof LivingEntity living)||!CreateLivingRuntime.matches(living,actor,tag.getCompound("LivingRuntime"))))throw new IllegalStateException("Боевое состояние цели изменилось");
            CartRestoreLocks.keepLoaded(level,current.blockPosition());}
        void verifyTerminal(CompoundTag tag){
            if(current==null||current.isRemoved()){
                if(candidate!=null&&tag.getCompound("Death").contains("Scores",10)){var scores=CreateScoreRuntime.capture(candidate);var expected=tag.getCompound("Death").getCompound("Scores").copy();for(var row:expected.getList("Objectives",10))((CompoundTag)row).remove("Score");if(!scores.equals(expected))throw new IllegalStateException("Scoreboard удалённой цели изменился");}
                return;
            }
            if(!(current instanceof LivingEntity living)||living.isAlive()||current.isPassenger()||!current.getPassengers().isEmpty()||!level.hasChunkAt(current.blockPosition()))throw new IllegalStateException("Умершая цель заменена или ожила");
            if(tag.getCompound("Death").contains("Scores",10)&&!CreateScoreRuntime.capture(current).equals(tag.getCompound("Death").getCompound("Scores")))throw new IllegalStateException("Scoreboard трупа изменился");
            var actual=NbtSerde.snapshotEntity(level,current);var expected=tag.getCompound("Death").getCompound("NBT");
            if(actual==null||!terminalStable(actual).equals(terminalStable(expected)))throw new IllegalStateException("Данные трупа изменились");
            var runtime=CreateLivingRuntime.capture(living,actor);var saved=tag.getCompound("Death").getCompound("LivingRuntime");
            if(!terminalRuntime(runtime).equals(terminalRuntime(saved)))throw new IllegalStateException("Состояние трупа изменилось");
        }
        CompoundTag terminalStable(CompoundTag value){var tag=stable(value);tag.remove("DeathTime");tag.remove("HurtTime");return tag;}
        CompoundTag terminalRuntime(CompoundTag value){var tag=value.copy();for(String key:List.of("Tick","AttackStrength","NoAction","HurtTime","DeathTime","Invulnerable","LastPlayerTime"))tag.remove(key);var p=tag.getCompound("Persistent");p.remove("HurtTime");p.remove("DeathTime");return tag;}
        static Set<String> differences(CompoundTag actual,CompoundTag expected){var keys=new TreeSet<>(actual.getAllKeys());keys.addAll(expected.getAllKeys());keys.removeIf(k->Objects.equals(actual.get(k),expected.get(k)));return keys;}
        void set(CompoundTag tag){var existing=level.getEntity(id);
            if(tag.getBoolean("Terminal")){
                if(!corpse){if(existing!=null&&!existing.isRemoved())existing.discard();if(candidate!=null&&tag.getCompound("Death").contains("Scores",10)){var empty=tag.getCompound("Death").getCompound("Scores").copy();for(var row:empty.getList("Objectives",10))((CompoundTag)row).remove("Score");CreateScoreRuntime.restore(candidate,empty);}verify(tag);return;}
                var dead=tag.getCompound("Death").copy();if(liveAfter!=null)dead.put("NBT",liveAfter.copy());if(deathRuntime!=null)dead.put("LivingRuntime",deathRuntime.copy());set(dead);verify(tag);return;
            }
            if(!tag.getBoolean("Exists")){if(existing!=null&&!existing.isRemoved())existing.discard();verify(tag);return;}
            if(existing==null||existing.isRemoved()){var restored=candidate!=null&&!candidate.isRemoved()?candidate:EntityType.loadEntityRecursive(tag.getCompound("NBT").copy(),level,e->e);if(restored==null||!id.equals(restored.getUUID())||!level.addFreshEntity(restored))throw new IllegalStateException("Мир отклонил возврат цели");}
            else {var restored=tag.getCompound("NBT").copy();var live=NbtSerde.snapshotEntity(level,existing);if(live==null)throw new IllegalStateException("Нет текущего снимка цели");
                for(String key:ticking){if(live.contains(key))restored.put(key,live.get(key).copy());else restored.remove(key);}existing.setCustomName(null);((com.roften.avilixlogger.compat.create.mixin.CreateOriginDataAccessor)existing).avilixlogger$originData(restored.contains("NeoForgeData",10)?restored.getCompound("NeoForgeData").copy():null);existing.load(restored);}
            if(tag.contains("LivingRuntime",10)&&level.getEntity(id) instanceof LivingEntity living)bindings.run(()->CreateLivingRuntime.restore(living,actor,tag.getCompound("LivingRuntime")));if(tag.contains("Scores",10))CreateScoreRuntime.restore(level.getEntity(id),tag.getCompound("Scores"));verify(tag);}
        public void verifyAfter(){verify(undo.after());}public void verifyBefore(){verify(undo.before());}public void apply(){set(undo.before());}
        public void compensate(){var expected=undo.after().copy();if(liveAfter!=null)expected.put("NBT",liveAfter.copy());set(expected);}
    }
}

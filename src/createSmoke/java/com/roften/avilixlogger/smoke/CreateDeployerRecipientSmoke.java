package com.roften.avilixlogger.smoke;
import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.*;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.deployer.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.effect.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import net.minecraft.stats.Stats;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.apache.commons.lang3.tuple.MutablePair;
import java.lang.reflect.*;
import java.util.*;
/** Native DeployerHandler feeds real ServerPlayer instances; only the network transport is a dummy. */
public final class CreateDeployerRecipientSmoke {
    public static void run(ServerLevel world)throws Exception{
        var pos=world.getSharedSpawnPos().offset(100,40,100);var visible=method(CreateFunnelSmoke.class,"visible",ServerLevel.class,BlockPos.class);visible.invoke(null,world,pos);
        var factory=method(CreateCargoSmoke.class,"entity",ServerLevel.class,CreateContraptionKind.class);var attach=method(CreateLinkedTransferSmoke.class,"attachCarriage",AbstractContraptionEntity.class);
        int tested=0;
        for(var kind:List.of(CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN))
        for(String mode:List.of("food","drink","drink-full","cancel-first","cancel-all","food-conflict","inventory-conflict","locks","logout","reward")){
            var body=(AbstractContraptionEntity)factory.invoke(null,world,kind);attach.invoke(null,body);body.setPos(Vec3.atCenterOf(pos.above(5)));check(world.addFreshEntity(body),"recipient body spawn");
            var profile=new com.mojang.authlib.GameProfile(UUID.randomUUID(),"feed_fixture");var player=new ServerPlayer(world.getServer(),world,profile,ClientInformation.createDefault());
            var dummy=net.neoforged.neoforge.common.util.FakePlayerFactory.get(world,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"feed_transport"));player.connection=dummy.connection;
            var byId=field(net.minecraft.server.players.PlayerList.class,"playersByUUID");@SuppressWarnings("unchecked")var players=(Map<UUID,ServerPlayer>)byId.get(world.getServer().getPlayerList());players.put(player.getUUID(),player);
            player.setPos(Vec3.atCenterOf(pos));world.addNewPlayer(player);player.getFoodData().setFoodLevel(7);player.getFoodData().setSaturation(1);
            var settings=new CompoundTag();settings.putString("Mode","USE");var info=new StructureBlockInfo(new BlockPos(7,0,0),AllBlocks.DEPLOYER.getDefaultState().setValue(DeployerBlock.FACING,Direction.EAST),settings);
            var context=new MovementContext(world,info,body.getContraption());context.position=Vec3.atCenterOf(pos);body.getContraption().getActors().add(MutablePair.of(info,context));
            var actor=new DeployerFakePlayer(world,UUID.randomUUID());context.temporaryData=actor;boolean drink=mode.startsWith("drink");var food=drink?Items.HONEY_BOTTLE:Items.APPLE;
            actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(food,2));player.getActiveEffectsMap().put(MobEffects.POISON,new MobEffectInstance(MobEffects.POISON,200));
            if(mode.equals("drink-full"))for(int slot=0;slot<41;slot++)player.getInventory().setItem(slot,new ItemStack(Items.STONE,64));
            var scoreboard=world.getScoreboard();String objectiveName="feed_"+tested;
            var objective=scoreboard.addObjective(objectiveName,Stats.ITEM_USED.get(food),Component.literal("native food counter"),ObjectiveCriteria.RenderType.INTEGER,false,null);
            var cachedScore=scoreboard.getOrCreatePlayerScore(player,objective,true);cachedScore.set(10);
            if(mode.equals("reward")){var holder=world.getServer().getAdvancements().get(net.minecraft.resources.ResourceLocation.parse("minecraft:husbandry/balanced_diet"));check(holder!=null,"food advancement missing");
                for(String criterion:player.getAdvancements().getOrStartProgress(holder).getRemainingCriteria())if(!criterion.equals("apple")&&!criterion.equals("minecraft:apple"))player.getAdvancements().award(holder,criterion);}
            var before=CreatePlayerConsumption.capture(player);var livingBefore=CreateLivingRuntime.capture(player,actor);var handBefore=CreateDeployerTransaction.hand(context,actor);LogEntry row=null;
            try{
                world.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
                try(var session=CreateDeployerAudit.capture(context,pos,actor,"USE")){
                    Class modeClass=Class.forName("com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity$Mode");
                    try{java.lang.invoke.MethodHandles.publicLookup().findVirtual(DeployerMovementBehaviour.class,"activate",java.lang.invoke.MethodType.methodType(void.class,MovementContext.class,BlockPos.class,DeployerFakePlayer.class,modeClass))
                        .invokeWithArguments(new DeployerMovementBehaviour(),context,pos,actor,Enum.valueOf(modeClass,"USE"));}
                    catch(Throwable failure){throw new IllegalStateException("native feeding",failure);}row=session.finish(true);
                }
                check(row!=null,"native feeding did not produce a row");row.materializeSnapshots();var b=NbtSerde.fromSnbt(row.beBefore);var a=NbtSerde.fromSnbt(row.beAfter);
                check(player.getFoodData().getFoodLevel()>7,"real recipient was not fed");check(row.actorUuid==null&&"SYSTEM".equals(row.actorName),"feeding blamed owner/player");
                if(mode.equals("reward")){check(!a.getBoolean("Complete"),"reward function effects allowed partial undo");reject(()->CreateTransferRollbackPlan.prepare(b,a),"reward action accepted");tested++;continue;}
                check(a.getBoolean("Complete"),"food capture incomplete: "+a.getString("JointFailure"));var after=CreatePlayerConsumption.capture(player);var livingAfter=CreateLivingRuntime.capture(player,actor);
                check(cachedScore.get()==11,"native stat objective not incremented");if(drink)check(!player.hasEffect(MobEffects.POISON),"native honey did not clear poison");
                var plan=CreateTransferRollbackPlan.prepare(b,a);check(plan.targets().stream().anyMatch(u->u.after().getString("Type").equals("minecraft:player")),"recipient not in shared plan");
                if(mode.equals("food-conflict"))player.getFoodData().setFoodLevel(2);
                if(mode.equals("inventory-conflict"))player.getInventory().setItem(3,new ItemStack(Items.EMERALD));
                var type=Class.forName("com.roften.avilixlogger.compat.create.CreateTransferRollbackCoordinator$Job");var ctor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CreateTransferRollbackPlan.class,long.class);ctor.setAccessible(true);
                var job=ctor.newInstance(world.getServer().createCommandSourceStack(),plan,1L);var step=method(type,"step",net.minecraft.server.MinecraftServer.class);var cancel=method(type,"cancel",String.class);var leaving=method(type,"leaving",ServerPlayer.class);var applied=field(type,"applied");
                boolean old=CartAuditContext.restoring(true);CartAuditContext.rollbackActive=true;boolean canceled=false,rejected=false,locked=false,departed=false;
                try{
                    for(int calls=0;;calls++){check(calls<1000,"feeding transaction did not terminate");boolean done;
                        try{done=(Boolean)step.invoke(job,world.getServer());}catch(InvocationTargetException failure){if(!mode.endsWith("conflict"))throw new IllegalStateException(kind+"/"+mode,failure.getCause());check(applied.getInt(job)==0,"preflight conflict wrote hand");rejected=true;break;}
                        int total=plan.cargo().size()+plan.hands().size()+plan.blocks().size()+plan.targets().size();
                        if(mode.startsWith("cancel")&&!canceled&&applied.getInt(job)>=(mode.equals("cancel-all")?total:1)){cancel.invoke(job,"native cancellation");canceled=true;}
                        if(mode.equals("logout")&&!departed&&applied.getInt(job)>=total){leaving.invoke(job,player);players.remove(player.getUUID());departed=true;canceled=true;check(CreatePlayerConsumption.capture(player).equals(after),"logout saved partially restored recipient");}
                        if(mode.equals("locks")&&!locked&&applied.getInt(job)>0){var saved=CreatePlayerConsumption.capture(player);CartAuditContext.restoring(false);
                            try{cachedScore.set(1000);scoreboard.getOrCreatePlayerScore(player,objective,true).set(1001);scoreboard.resetSinglePlayerScore(player,objective);scoreboard.removeObjective(objective);
                                player.awardStat(Stats.ITEM_USED.get(food),100);player.getInventory().setItem(3,new ItemStack(Items.EMERALD));
                                check(CreatePlayerConsumption.capture(player).equals(saved),"reserved score/stat/inventory changed");}
                            finally{CartAuditContext.restoring(true);}locked=true;}
                        if(done)break;
                    }
                    if(mode.endsWith("conflict"))check(rejected,"recipient conflict accepted");
                    else if(canceled){check(CreatePlayerConsumption.capture(player).equals(after)&&CreateLivingRuntime.matches(player,actor,livingAfter),"recipient compensation lost state");check(CreateDeployerTransaction.hand(context,actor).equals(a.getCompound("Hand")),"hand compensation lost state");}
                    else{check(field(type,"committed").getBoolean(job),"feeding not committed");check(CreatePlayerConsumption.capture(player).equals(before)&&CreateLivingRuntime.matches(player,actor,livingBefore),"recipient state not restored");check(CreateDeployerTransaction.hand(context,actor).equals(handBefore),"consumed hand not restored");if(mode.equals("locks"))check(locked,"locks not exercised");}
                }finally{CartAuditContext.restoring(old);CartAuditContext.rollbackActive=false;CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();}
                tested++;
            }finally{boolean old=CartAuditContext.restoring(true);try{players.remove(player.getUUID());world.removePlayerImmediately(player,Entity.RemovalReason.DISCARDED);scoreboard.removeObjective(objective);
                    if(row!=null&&row.beAfter!=null){var tag=NbtSerde.fromSnbt(row.beAfter);if(tag!=null)for(var raw:tag.getList("Targets",10)){var id=((CompoundTag)raw).getUUID("Entity");var e=world.getEntity(id);if(e!=null&&e!=player)e.discard();}}
                    ((CartEntityAccess)body).avilixlogger$discardForRollback();}finally{CartAuditContext.restoring(old);CartRestoreLocks.clear();}}
        }
        System.out.println("CREATE_DEPLOYER_RECIPIENT_NATIVE_OK: "+tested+" real-player food/drink, hand/inventory/effects/stats/scoreboard, compensation, locks, logout and reward rejection scenarios");
    }
    private static Method method(Class<?> type,String name,Class<?>...args)throws Exception{var m=type.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    private static Field field(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void reject(Runnable action,String text){try{action.run();}catch(RuntimeException expected){return;}throw new AssertionError(text);}
    private static void check(boolean condition,String text){if(!condition)throw new AssertionError(text);}
}

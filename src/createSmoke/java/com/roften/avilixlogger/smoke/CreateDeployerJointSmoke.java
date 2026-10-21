package com.roften.avilixlogger.smoke;
import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.contraptions.*;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.deployer.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.tuple.MutablePair;
import java.lang.reflect.*;
import java.util.*;
/** Real native activate + same reserved compensated Job, without journal authorization overrides. */
public final class CreateDeployerJointSmoke {
    @net.neoforged.fml.common.EventBusSubscriber(modid="avilixlogger") public static final class Probe {
        @net.neoforged.bus.api.SubscribeEvent public static void start(net.neoforged.neoforge.event.server.ServerStartedEvent e){if(!Boolean.getBoolean("avilixlogger.deployerJointSmoke"))return;try{run(e.getServer().overworld());}catch(Throwable failure){failure.printStackTrace();System.out.println("CREATE_DEPLOYER_JOINT_FAILED");}finally{e.getServer().halt(false);}}
    }
    public static void run(ServerLevel world)throws Exception {
        var board=world.getScoreboard();
        for(var entry:Map.of("avilix_kills",net.minecraft.world.scores.criteria.ObjectiveCriteria.KILL_COUNT_ALL,"avilix_apple",net.minecraft.stats.Stats.ITEM_USED.get(Items.APPLE),"avilix_team_kill",net.minecraft.world.scores.criteria.ObjectiveCriteria.TEAM_KILL[net.minecraft.ChatFormatting.RED.getId()],"avilix_team_death",net.minecraft.world.scores.criteria.ObjectiveCriteria.KILLED_BY_TEAM[net.minecraft.ChatFormatting.BLUE.getId()]).entrySet())if(board.getObjective(entry.getKey())==null)board.addObjective(entry.getKey(),entry.getValue(),Component.literal(entry.getKey()),net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER,false,null);
        var blue=board.getPlayerTeam("avilix_blue");if(blue==null)blue=board.addPlayerTeam("avilix_blue");blue.setColor(net.minecraft.ChatFormatting.BLUE);
        var red=board.getPlayerTeam("avilix_red");if(red==null)red=board.addPlayerTeam("avilix_red");red.setColor(net.minecraft.ChatFormatting.RED);
        runtimeRoundTrip(world);
        var factory=method(CreateCargoSmoke.class,"entity",ServerLevel.class,CreateContraptionKind.class);var attach=method(CreateLinkedTransferSmoke.class,"attachCarriage",AbstractContraptionEntity.class);
        var pos=world.getSharedSpawnPos().offset(40,40,40);method(CreateFunnelSmoke.class,"visible",ServerLevel.class,BlockPos.class).invoke(null,world,pos);
        var modes=new ArrayList<String>(List.of("place","place-cancel-first","place-cancel-all","water","lever","name","name-cancel-all","frame","frame-cancel-all","hand-conflict","world-conflict","entity-conflict","locks","failure","noop","combat","combat-cancel-first","combat-cancel-all","combat-removed","combat-removed-cancel-all","combat-dead-conflict","combat-runtime-conflict","combat-cardboard","combat-cardboard-cancel-first","combat-cardboard-cancel-all","combat-cardboard-conflict","feed-create-chocolate","feed-create-roll","feed-create-berries","feed-create-apple","feed-create-tea","feed-create-tea-cancel-first","feed-create-tea-cancel-all","feed-create-tea-conflict","feed-create-tea-stack","feed-create-tea-stack-cancel-all","feed-create-tea-bottle-conflict","feed-create-tea-full","feed","feed-drink","feed-drink-cancel-first","feed-drink-cancel-all","feed-drink-effect-conflict","feed-drink-stat-conflict","feed-unrelated-progress","feed-natural-ticks","feed-cancel-first","feed-cancel-all","feed-stat-conflict","feed-adv-conflict","feed-inventory-conflict","feed-locks","consume-loose","consume-loose-cancel-all","consume-loose-conflict","combat-live","combat-live-cancel-first","combat-live-cancel-all","combat-live-conflict","consume-polish","consume-polish-cancel-all","consume-polish-conflict","name-move","name-locks","shear","shear-cancel-all","frame-break","frame-break-cancel-all","cargo-grab","cargo-return","cargo-conflict","cargo-cancel-all","place-oversized"));
        if(net.neoforged.fml.ModList.get().isLoaded("farmersdelight")){
            for(var id:net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet().stream().filter(k->k.getNamespace().equals("farmersdelight")).sorted().toList()){
                var stack=new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id));
                if(stack.has(DataComponents.FOOD)&&CreateDeployerTransaction.supportedItem(stack,null))modes.add("feed-fd/"+id.getPath()+"/native");
            }
            modes.add("feed-fd/dog_food/barrier");modes.add("feed-fd/horse_feed/barrier");
            for(String food:List.of("beef_stew","apple_cider","milk_bottle","hot_cocoa","melon_juice","melon_popsicle"))for(String phase:List.of("native","stack","cancel-first","cancel-all","effect-conflict","full"))modes.add((food.equals("melon_popsicle")?"finish-fd/":List.of("milk_bottle","hot_cocoa","melon_juice").contains(food)?"consume-fd/":"feed-fd/")+food+"/"+phase);
            modes.add("self-noop-fd/melon_popsicle/native");
        }
        if(net.neoforged.fml.ModList.get().isLoaded("backpacked"))for(String participant:List.of("backpack-progress-","backpack-inventory-","feed-backpack-progress-"))for(String phase:List.of("native","cancel-first","cancel-all","conflict","unrelated","locks"))modes.add(participant+phase);
        int tested=0;
        for(var kind:List.of(CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER))
        for(String mode:modes){
            var body=(AbstractContraptionEntity)factory.invoke(null,world,kind);attach.invoke(null,body);body.setPos(pos.above(5).getX(),pos.above(5).getY(),pos.above(5).getZ());check(world.addFreshEntity(body),"deployer construction spawn");Entity target=null;LogEntry row=null;
            try{
                world.setBlock(pos.below(),Blocks.STONE.defaultBlockState(),3);world.setBlock(pos,mode.equals("lever")?Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE,net.minecraft.world.level.block.state.properties.AttachFace.FLOOR):Blocks.AIR.defaultBlockState(),3);
                boolean nonlethal=mode.startsWith("combat-live")||mode.startsWith("combat-cardboard");
                boolean punch=mode.startsWith("combat")||mode.startsWith("frame-break");
                var actorPos=new BlockPos(7,0,0);var settings=new CompoundTag();settings.putUUID("Owner",UUID.randomUUID());settings.putString("Mode",punch?"PUNCH":"USE");
                var info=new StructureBlockInfo(actorPos,AllBlocks.DEPLOYER.getDefaultState().setValue(DeployerBlock.FACING,Direction.EAST),settings);var context=new MovementContext(world,info,body.getContraption());context.position=Vec3.atCenterOf(pos);body.getContraption().getActors().add(MutablePair.of(info,context));
                var fake=new DeployerFakePlayer(world,settings.getUUID("Owner"));context.temporaryData=fake;fake.getInventory().selected=1;board.addPlayerToTeam(fake.getScoreboardName(),blue);
                ItemStack held=new ItemStack(mode.equals("water")?Items.WATER_BUCKET:mode.startsWith("name")||mode.equals("entity-conflict")?Items.NAME_TAG:mode.startsWith("shear")?Items.SHEARS:mode.startsWith("frame")?Items.DIAMOND:mode.startsWith("combat")&&!nonlethal?Items.DIAMOND_SWORD:mode.equals("lever")||mode.equals("noop")||mode.startsWith("backpack-")?Items.AIR:Items.STONE);
                if(nonlethal)held=new ItemStack(Items.WOODEN_SWORD);
                if(mode.startsWith("combat-cardboard")){held=AllItems.CARDBOARD_SWORD.asStack();fake.setSprinting(true);fake.setDeltaMovement(.5,0,.5);}
                if(mode.startsWith("feed"))held=mode.startsWith("feed-drink")?new ItemStack(Items.MILK_BUCKET):new ItemStack(Items.APPLE,2);
                if(mode.startsWith("feed-create"))held=new ItemStack(mode.startsWith("feed-create-tea")?AllItems.BUILDERS_TEA.get():mode.equals("feed-create-chocolate")?AllItems.BAR_OF_CHOCOLATE.get():mode.equals("feed-create-roll")?AllItems.SWEET_ROLL.get():mode.equals("feed-create-berries")?AllItems.CHOCOLATE_BERRIES.get():AllItems.HONEYED_APPLE.get(),mode.contains("stack")||mode.contains("bottle-conflict")||mode.endsWith("full")?2:1);
                if(mode.startsWith("feed-fd/")||mode.startsWith("consume-fd/")||mode.startsWith("finish-fd/")||mode.startsWith("self-noop-fd/")){var id=ResourceLocation.fromNamespaceAndPath("farmersdelight",mode.split("/")[1]);check(net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id),"missing FD fixture "+id);held=new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id),mode.endsWith("stack")||mode.endsWith("full")?2:1);}
                if(mode.startsWith("consume-fd/")||mode.startsWith("finish-fd/")||mode.startsWith("self-noop-fd/")){fake.setHealth(12);fake.setRemainingFireTicks(100);fake.getFoodData().setFoodLevel(7);if(mode.contains("milk_bottle")||mode.contains("hot_cocoa"))fake.getActiveEffectsMap().put(MobEffects.POISON,new MobEffectInstance(MobEffects.POISON,200));if(mode.endsWith("full"))for(int slot=0;slot<36;slot++)if(slot!=1)fake.getInventory().setItem(slot,new ItemStack(Items.EMERALD,64));}
                if(mode.startsWith("consume-loose"))held=AllItems.SAND_PAPER.asStack();
                if(mode.startsWith("consume-polish")){
                    held=new ItemStack(AllItems.SAND_PAPER.get());fake.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(AllItems.ROSE_QUARTZ.get(),2));
                    fake.getFoodData().setFoodLevel(7);fake.getFoodData().setSaturation(2);
                    fake.getCooldowns().addCooldown(Items.ENDER_PEARL,40);
                }
                if(mode.equals("place-oversized")){held.setCount(1024);held.set(DataComponents.CUSTOM_NAME,Component.literal("large hand component"));}
                if(held.is(Items.NAME_TAG))held.set(DataComponents.CUSTOM_NAME,Component.literal("deployer named target"));fake.setItemInHand(InteractionHand.MAIN_HAND,held);fake.getInventory().setItem(37,new ItemStack(Items.IRON_CHESTPLATE));
                if(mode.startsWith("name")||mode.startsWith("shear")||mode.equals("entity-conflict")||mode.startsWith("combat")){var sheep=EntityType.SHEEP.create(world);if(!nonlethal&&mode.startsWith("combat"))sheep.setHealth(1);sheep.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);check(world.addFreshEntity(sheep),"target sheep spawn");target=sheep;}
                if(mode.startsWith("feed")){
                    var profile=new com.mojang.authlib.GameProfile(UUID.randomUUID(),"FeedRecipient");var human=new net.minecraft.server.level.ServerPlayer(world.getServer(),world,profile,net.minecraft.server.level.ClientInformation.createDefault());
                    // This is a server-state fixture, without a negotiated client connection.
                    // Sink outbound packets before NeoForge channel capability checks.
                    human.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(world.getServer(),new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND),human,net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false)){
                        @Override public void send(net.minecraft.network.protocol.Packet<?> packet){}
                        @Override public void send(net.minecraft.network.protocol.Packet<?> packet,net.minecraft.network.PacketSendListener callback){}
                    };
                    human.setPos(Vec3.atCenterOf(pos));human.getFoodData().setFoodLevel(7);human.getFoodData().setSaturation(2);if(mode.startsWith("feed-fd/")){human.setHealth(12);human.setRemainingFireTicks(100);if(mode.contains("milk_bottle")||mode.contains("hot_cocoa"))human.addEffect(new MobEffectInstance(MobEffects.POISON,200));}
                    if(mode.startsWith("feed-drink"))human.addEffect(new MobEffectInstance(MobEffects.POISON,200));human.getInventory().setItem(8,new ItemStack(Items.EMERALD,1024));if(mode.equals("feed-create-tea-full")||mode.startsWith("feed-fd/")&&mode.endsWith("full"))for(int slot=0;slot<36;slot++)human.getInventory().setItem(slot,new ItemStack(Items.EMERALD,64));world.addNewPlayer(human);target=human;
                }
                if(mode.startsWith("consume-loose")){world.setBlock(pos.east(),Blocks.STONE.defaultBlockState(),3);var loose=new net.minecraft.world.entity.item.ItemEntity(world,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,new ItemStack(AllItems.ROSE_QUARTZ.get(),2));loose.setNoGravity(true);check(world.addFreshEntity(loose),"loose polish input spawn");target=loose;}
                if(mode.startsWith("frame")){world.setBlock(pos.west(),Blocks.STONE.defaultBlockState(),3);var frame=new ItemFrame(world,pos,Direction.EAST);check(world.addFreshEntity(frame),"target frame spawn");target=frame;}
                if(target instanceof LivingEntity){board.addPlayerToTeam(target.getScoreboardName(),red);if(mode.startsWith("combat")&&!nonlethal)board.getOrCreatePlayerScore(target,board.getObjective("avilix_team_death"),true).set(7);}
                var items=body.getContraption().getStorage().getAllItemStorages().get(BlockPos.ZERO);
                items.setStackInSlot(0,new ItemStack(Items.DIAMOND,7));
                if(mode.equals("cargo-grab")||mode.equals("cargo-conflict"))fake.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                var cargoBefore=new ArrayList<ItemStack>();for(int slot=0;slot<items.getSlots();slot++)cargoBefore.add(items.getStackInSlot(slot).copy());
                if(mode.startsWith("backpack-inventory-"))backpackFixture(fake,2);
                var before=CreateDeployerTransaction.hand(context,fake);
                int heldCount=held.getCount();var consumed=held.getItem();var food=held.getFoodProperties(target instanceof LivingEntity living?living:fake);int fedLevel=food==null?7:Math.min(20,7+food.nutrition());
                var humanBefore=target instanceof net.minecraft.server.level.ServerPlayer human?CreateHumanRestore.capture(human):null;
                try(var session=CreateDeployerAudit.capture(context,pos,fake,punch?"PUNCH":"USE")){
                    if(mode.startsWith("finish-fd/")){
                        // Create refuses to eat ordinary food itself. Exercise the real FD
                        // finish callback separately, without changing DeployerHandler rules.
                        try(var use=CreateDeployerTransaction.using("item_finish")){var result=fake.getMainHandItem().finishUsingItem(world,fake);use.returned(result);fake.setItemInHand(InteractionHand.MAIN_HAND,result);}
                        CreateDeployerAudit.used("item_use",true,null);
                    }else if(mode.startsWith("consume-loose")){
                        // Create's ItemUseWorld has a private empty entity getter. Exercise
                        // the real-level sandpaper endpoint without changing Create's rules.
                        fake.setPos(pos.getX()-.5,pos.getY()+.5,pos.getZ()+.5);fake.setYRot(-90);fake.setXRot(0);
                        try(var use=CreateDeployerTransaction.using("item_use")){var result=fake.getMainHandItem().getItem().use(world,fake,InteractionHand.MAIN_HAND);check(result.getResult().consumesAction(),"native loose use did not select input");fake.setItemInHand(InteractionHand.MAIN_HAND,result.getObject());}
                        try(var use=CreateDeployerTransaction.using("item_finish")){var result=fake.getMainHandItem().finishUsingItem(world,fake);use.returned(result);fake.setItemInHand(InteractionHand.MAIN_HAND,result);}
                        CreateDeployerAudit.used("item_use",true,target);
                    }else if(mode.startsWith("cargo")){
                        String name=mode.equals("cargo-grab")||mode.equals("cargo-conflict")?"tryGrabbingItem":"tryDisposeOfExcess";
                        try { java.lang.invoke.MethodHandles.privateLookupIn(DeployerMovementBehaviour.class,java.lang.invoke.MethodHandles.lookup())
                            .findVirtual(DeployerMovementBehaviour.class,name,java.lang.invoke.MethodType.methodType(void.class,MovementContext.class)).invokeWithArguments(new DeployerMovementBehaviour(),context); }
                        catch(Throwable failure){throw new IllegalStateException("Native hand/cargo",failure);}
                    } else {
                    Class modeClass=Class.forName("com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity$Mode");
                    try { java.lang.invoke.MethodHandles.publicLookup().findVirtual(DeployerMovementBehaviour.class,"activate",
                        java.lang.invoke.MethodType.methodType(void.class,MovementContext.class,BlockPos.class,DeployerFakePlayer.class,modeClass))
                        .invokeWithArguments(new DeployerMovementBehaviour(),context,pos,fake,Enum.valueOf(modeClass,punch?"PUNCH":"USE")); } catch(Throwable failure) { throw new IllegalStateException("Native activate",failure); }
                    }
                    if(mode.contains("backpack-progress-"))check(com.mrcrayfish.backpacked.core.ModSyncedDataKeys.UNLOCK_TRACKER.getValue(mode.startsWith("feed-")?(net.minecraft.world.entity.player.Player)target:fake).unlockBackpack(ResourceLocation.parse("backpacked:cogwheel")),"backpack progress fixture did not unlock");
                    if(mode.startsWith("backpack-inventory-"))backpackFixture(fake,12);
                    row=session.finish(!mode.equals("failure"));
                }
                if(mode.equals("noop")||mode.startsWith("self-noop-fd/")){check(row==null,"idle/no-op activation invented a row");if(mode.startsWith("self-noop-fd/"))check(fake.getRemainingFireTicks()==100&&fake.getMainHandItem().is(held.getItem())&&fake.getMainHandItem().getCount()==heldCount,"native self-food refusal changed state");tested++;continue;}
                check(row!=null,"native deployer action not captured: "+kind+"/"+mode);row.materializeSnapshots();var b=NbtSerde.fromSnbt(row.beBefore);var a=NbtSerde.fromSnbt(row.beAfter);
                check(row.actorUuid==null&&"SYSTEM".equals(row.actorName),"deployer blamed its owner");check(CreateContraptionKind.id(row.source).equals(CreateContraptionSources.id(body)),"deployer root ID lost");
                if(mode.equals("failure")||mode.endsWith("/barrier")){check(!a.getBoolean("Complete"),"partial/unsupported attack enabled undo");reject(()->CreateTransferRollbackPlan.prepare(b,a),"partial/unsupported deployer accepted");tested++;continue;}
                check(a.getBoolean("Complete"),"capture incomplete: "+kind+"/"+mode+" reason="+a.getString("JointFailure"));
                if(mode.equals("feed-natural-ticks")){var human=(net.minecraft.server.level.ServerPlayer)target;for(int t=0;t<20;t++){human.doTick();world.tickNonPassenger(human);}}
                if(mode.equals("feed-unrelated-progress")||mode.equals("feed-natural-ticks")){
                    var human=(net.minecraft.server.level.ServerPlayer)target;human.awardStat(net.minecraft.stats.Stats.PLAY_TIME,20);human.getCooldowns().tick();var holder=world.getServer().getAdvancements().get(ResourceLocation.parse("minecraft:husbandry/plant_seed"));human.getAdvancements().award(holder,holder.value().criteria().keySet().iterator().next());
                    humanBefore=CreateHumanRestore.capture(human);var from=a.getList("Targets",10).getCompound(0).getCompound("Human");var to=b.getList("Targets",10).getCompound(0).getCompound("Human");humanBefore.put("Progress",CreatePlayerProgress.merge(humanBefore.getCompound("Progress"),from.getCompound("Progress"),to.getCompound("Progress")));humanBefore.put("Scores",CreateScoreRuntime.merge(humanBefore.getCompound("Scores"),from.getCompound("Scores"),to.getCompound("Scores")));
                }
                var plan=CreateTransferRollbackPlan.prepare(b,a);
                if(mode.startsWith("combat")&&!nonlethal){check(!target.isAlive()&&a.getList("Targets",10).stream().anyMatch(t->((CompoundTag)t).getBoolean("Terminal")),"lethal activation was not captured");if(mode.contains("removed"))target.discard();}
                if(mode.startsWith("feed")){var human=(net.minecraft.server.level.ServerPlayer)target;boolean drink=mode.startsWith("feed-drink");check(human.getFoodData().getFoodLevel()==fedLevel&&human.getStats().getValue(net.minecraft.stats.Stats.ITEM_USED.get(consumed))==1,"native feed/drink did not change recipient/counter: "+mode+" food="+human.getFoodData().getFoodLevel()+" expected="+fedLevel+" stat="+human.getStats().getValue(net.minecraft.stats.Stats.ITEM_USED.get(consumed)));
                    if(drink)check(fake.getMainHandItem().is(Items.BUCKET)&&!human.hasEffect(MobEffects.POISON),"milk remainder/effect missing");
                    if(mode.startsWith("feed-create-tea")){check(human.hasEffect(MobEffects.DIG_SPEED),"builder tea haste missing");if(heldCount==1)check(fake.getMainHandItem().is(Items.GLASS_BOTTLE),"empty tea hand lost bottle");else check(fake.getMainHandItem().getCount()==1&&human.getInventory().countItem(Items.GLASS_BOTTLE)==(mode.endsWith("full")?0:1),"stacked tea bottle endpoint wrong");}
                }
                if(mode.startsWith("consume-loose"))check(((net.minecraft.world.entity.item.ItemEntity)target).getItem().getCount()==1&&fake.getInventory().countItem(AllItems.POLISHED_ROSE_QUARTZ.get())==1,"native loose polishing did not finish");
                if(nonlethal&&!mode.startsWith("combat-cardboard"))check(target.isAlive()&&((LivingEntity)target).getHealth()<8&&a.getInt("JointFormat")>=3,"nonlethal combat runtime was not captured");
                if(mode.startsWith("consume-fd/")||mode.startsWith("finish-fd/")){
                    if(mode.contains("milk_bottle")||mode.contains("hot_cocoa"))check(!fake.hasEffect(MobEffects.POISON),"FD self drink did not cure effect: "+mode);
                    if(mode.contains("melon_juice"))check(fake.getHealth()==14,"FD self juice did not heal");
                    if(mode.contains("melon_popsicle"))check(!fake.isOnFire(),"FD self popsicle did not extinguish");
                }
                if(mode.startsWith("feed-fd/")){
                    var human=(net.minecraft.server.level.ServerPlayer)target;
                    if(mode.contains("milk_bottle")||mode.contains("hot_cocoa"))check(!human.hasEffect(MobEffects.POISON),"FD drink did not cure effect: "+mode);
                    if(mode.contains("melon_juice"))check(human.getHealth()==14,"FD juice did not heal");
                    if(mode.contains("melon_popsicle"))check(!human.isOnFire(),"FD popsicle did not extinguish");
                    if(mode.contains("beef_stew"))check(human.hasEffect(net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse("farmersdelight:nourishment")).orElseThrow()),"FD nourishment missing");
                }
                if(mode.startsWith("combat-cardboard"))check(((LivingEntity)target).getHealth()==8&&((LivingEntity)target).hasEffect(MobEffects.MOVEMENT_SLOWDOWN)&&target.getDeltaMovement().lengthSqr()>0,"native cardboard sword knockback/slowness missing");
                if(mode.startsWith("consume-polish"))check(fake.getOffhandItem().getCount()==1&&fake.getInventory().countItem(AllItems.POLISHED_ROSE_QUARTZ.get())==1,"native sandpaper consumption did not finish");
                var noRuntime=a.copy();noRuntime.getCompound("Hand").getCompound("Runtime").remove("Living");reject(()->CreateTransferRollbackPlan.prepare(b,noRuntime),"new-format action lost its living runtime");
                var invalid=a.copy();invalid.getCompound("Hand").putUUID("StorageEntity",UUID.randomUUID());reject(()->CreateTransferRollbackPlan.prepare(b,invalid),"wrong hand identity accepted");
                var missing=a.copy();missing.remove("Targets");reject(()->CreateTransferRollbackPlan.prepare(b,missing),"missing target list accepted");
                check(plan.hands().size()==1,"joint plan omitted hand");check(CartLogDetails.describe(world,row).stream().anyMatch(c->c.getString().contains("рука и цели")),"GUI omitted joint operation");
                if(mode.startsWith("place")||mode.equals("hand-conflict")||mode.equals("world-conflict")||mode.equals("locks"))check(world.getBlockState(pos).is(Blocks.STONE),"native stone placement missing");
                if(mode.equals("water"))check(!world.getFluidState(pos).isEmpty()&&fake.getMainHandItem().is(Items.BUCKET),"native bucket exchange missing");
                if(mode.startsWith("name"))check(target.hasCustomName(),"native target naming missing");
                if(mode.startsWith("frame")&&!mode.startsWith("frame-break"))check(!((ItemFrame)target).getItem().isEmpty(),"native frame insert missing");
                var pose=target==null?null:target.position();
                if(mode.equals("name-move")){target.setPos(target.getX()+1,target.getY(),target.getZ()+1);pose=target.position();}
                if(mode.startsWith("shear"))check(((Sheep)target).isSheared()&&plan.targets().size()>=1,"native wool/sheep endpoints missing");
                if(mode.startsWith("frame-break"))check(target.isRemoved()&&plan.targets().size()>=1,"native removed frame/drop endpoints missing");
                if(mode.startsWith("cargo"))check(!plan.cargo().isEmpty(),"hand/cargo exchange omitted mounted storage");
                if(mode.equals("cargo-conflict"))items.setStackInSlot(0,new ItemStack(Items.EMERALD));
                if(mode.equals("hand-conflict"))fake.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.EMERALD));
                if(mode.equals("combat-runtime-conflict"))CreateDeployerTransaction.clearRetained();
                if(mode.equals("combat-dead-conflict"))((LivingEntity)target).setHealth(2);
                if((mode.startsWith("consume-fd/")||mode.startsWith("finish-fd/"))&&mode.endsWith("effect-conflict"))fake.setHealth(1);
                if(mode.startsWith("feed-fd/")&&mode.endsWith("effect-conflict"))((net.minecraft.server.level.ServerPlayer)target).getFoodData().setFoodLevel(1);
                if(mode.equals("combat-cardboard-conflict"))target.setDeltaMovement(Vec3.ZERO);
                if(mode.equals("feed-create-tea-conflict"))((LivingEntity)target).removeEffect(MobEffects.DIG_SPEED);
                if(mode.equals("feed-create-tea-bottle-conflict")){var inv=((net.minecraft.server.level.ServerPlayer)target).getInventory();for(int slot=0;slot<41;slot++)if(inv.getItem(slot).is(Items.GLASS_BOTTLE)){inv.setItem(slot,ItemStack.EMPTY);break;}}
                if(mode.equals("feed-drink-effect-conflict"))((LivingEntity)target).addEffect(new MobEffectInstance(MobEffects.POISON,100));
                if(mode.equals("feed-drink-stat-conflict"))((net.minecraft.server.level.ServerPlayer)target).awardStat(net.minecraft.stats.Stats.ITEM_USED.get(Items.MILK_BUCKET));
                if(mode.equals("feed-stat-conflict"))((net.minecraft.server.level.ServerPlayer)target).awardStat(net.minecraft.stats.Stats.ITEM_USED.get(Items.APPLE));
                if(mode.equals("feed-adv-conflict")){var human=(net.minecraft.server.level.ServerPlayer)target;var holder=world.getServer().getAdvancements().get(ResourceLocation.parse("minecraft:husbandry/balanced_diet"));check(human.getAdvancements().revoke(holder,"apple"),"feed advancement conflict did not remove consumed-food criterion");}
                if(mode.equals("feed-inventory-conflict"))((net.minecraft.server.level.ServerPlayer)target).getInventory().setItem(8,new ItemStack(Items.BARRIER));
                if(mode.equals("consume-loose-conflict"))((net.minecraft.world.entity.item.ItemEntity)target).setItem(new ItemStack(Items.BARRIER));
                if(mode.equals("combat-live-conflict"))((LivingEntity)target).setHealth(1);
                if(mode.equals("consume-polish-conflict"))fake.getFoodData().setFoodLevel(9);
                if(mode.equals("world-conflict"))world.setBlock(pos,Blocks.GOLD_BLOCK.defaultBlockState(),3);
                if(mode.equals("entity-conflict"))target.setCustomName(Component.literal("later target change"));
                if(mode.contains("backpack-")&&mode.endsWith("conflict")){
                    var owner=mode.startsWith("feed-")?(net.minecraft.world.entity.player.Player)target:fake;
                    if(mode.contains("progress-"))com.mrcrayfish.backpacked.core.ModSyncedDataKeys.UNLOCK_TRACKER.setValue(owner,new com.mrcrayfish.backpacked.data.unlock.UnlockTracker());
                    else {var bag=com.mrcrayfish.backpacked.core.ModSyncedDataKeys.BACKPACKS.getValue(owner).get(0).copy();bag.set(DataComponents.CUSTOM_NAME,Component.literal("later bag change"));com.mrcrayfish.backpacked.core.ModSyncedDataKeys.BACKPACKS.getValue(owner).set(0,bag);}
                }
                if(mode.contains("backpack-")&&mode.endsWith("unrelated")){
                    var owner=mode.startsWith("feed-")?(net.minecraft.world.entity.player.Player)target:fake;
                    com.mrcrayfish.backpacked.core.ModSyncedDataKeys.SELECTED_BACKPACK.setValue(owner,2);
                    var expected=mode.startsWith("feed-")?humanBefore:before;
                    expected.getCompound("External").getCompound("Backpacked").getCompound("State").putInt(com.mrcrayfish.backpacked.core.ModSyncedDataKeys.SELECTED_BACKPACK.id().toString(),2);
                }
                var recipientLive=target instanceof net.minecraft.server.level.ServerPlayer?CreateLivingRuntime.entitySnapshot(world,target):null;
                var type=Class.forName("com.roften.avilixlogger.compat.create.CreateTransferRollbackCoordinator$Job");var ctor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CreateTransferRollbackPlan.class,long.class);ctor.setAccessible(true);var job=ctor.newInstance(world.getServer().createCommandSourceStack(),plan,1L);var step=method(type,"step",net.minecraft.server.MinecraftServer.class);var cancel=method(type,"cancel",String.class);var applied=field(type,"applied");
                boolean canceled=false,rejected=false,locked=false;boolean prior=CartAuditContext.restoring(true);CartAuditContext.rollbackActive=true;
                try{
                    for(int calls=0;;calls++){
                        check(calls<1000,"deployer rollback did not terminate");boolean done;
                        try{done=(Boolean)step.invoke(job,world.getServer());}catch(InvocationTargetException e){if(!mode.endsWith("conflict"))throw new IllegalStateException(kind+"/"+mode,e.getCause());check(applied.getInt(job)==0,"deployer preflight conflict wrote hand");rejected=true;break;}
                        int total=plan.cargo().size()+plan.hands().size()+plan.blocks().size()+plan.targets().size();
                        if(mode.contains("cancel")&&!canceled&&applied.getInt(job)>=(mode.endsWith("all")?total:1)){cancel.invoke(job,"deployer partial cancellation");canceled=true;}
                        if((mode.equals("locks")||mode.equals("name-locks")||mode.equals("feed-locks")||mode.contains("backpack-")&&mode.endsWith("locks"))&&!locked&&applied.getInt(job)>0){CartAuditContext.restoring(false);try{fake.getInventory().setItem(1,new ItemStack(Items.EMERALD));check(!fake.getMainHandItem().is(Items.EMERALD),"reserved fake inventory changed");if(mode.equals("locks"))check(!world.setBlock(pos,Blocks.GOLD_BLOCK.defaultBlockState(),3),"reserved deployer target changed");
                            if(mode.contains("backpack-")){
                                var owner=mode.startsWith("feed-")?(net.minecraft.world.entity.player.Player)target:fake;
                                var external=CreateExternalPlayerState.capture(owner);
                                com.mrcrayfish.backpacked.core.ModSyncedDataKeys.SELECTED_BACKPACK.setValue(owner,3);
                                check(!com.mrcrayfish.backpacked.core.ModSyncedDataKeys.UNLOCK_TRACKER.getValue(owner).unlockBackpack(ResourceLocation.parse("backpacked:cogwheel")),"reserved unlock was accepted");
                                for(var progress:com.mrcrayfish.backpacked.core.ModSyncedDataKeys.UNLOCK_TRACKER.getValue(owner).getProgressTrackerMap().values())if(progress instanceof com.mrcrayfish.backpacked.common.tracker.impl.CountProgressTracker count)count.increment(1,(net.minecraft.server.level.ServerPlayer)owner);
                                if(mode.startsWith("backpack-inventory-")){
                                    var bagInventory=((com.mrcrayfish.backpacked.inventory.BackpackedInventoryAccess)owner).backpacked$GetBackpackInventory(0);
                                    bagInventory.setItem(0,new ItemStack(Items.EMERALD));check(bagInventory.getItem(0).is(Items.DIAMOND),"reserved backpack contents changed");
                                    check(!com.mrcrayfish.backpacked.BackpackHelper.setBackpackStack(owner,ItemStack.EMPTY,0),"reserved backpack unequipped");
                                }
                                check(CreateExternalPlayerState.capture(owner).equals(external),"reserved external state changed");
                            }
                            if(target instanceof net.minecraft.server.level.ServerPlayer human){
                                var stats=CreateHumanRestore.capture(human);human.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(Items.APPLE));
                                var holder=world.getServer().getAdvancements().get(ResourceLocation.parse("minecraft:husbandry/plant_seed"));human.getAdvancements().award(holder,holder.value().criteria().keySet().iterator().next());
                                check(CreateHumanRestore.capture(human).equals(stats),"reserved recipient statistics/advancement changed");
                            }
                            if(target!=null){target.discard();check(!target.isRemoved(),"reserved entity removed");
                            int tickBefore=target.tickCount;world.tickNonPassenger(target);check(target.tickCount==tickBefore,"reserved entity tick advanced between restore steps");
                            check(!target.hurt(world.damageSources().generic(),100),"reserved entity accepted environmental damage");
                            var named=target.getCustomName();target.setCustomName(Component.literal("external reserved write"));check(Objects.equals(named,target.getCustomName()),"reserved entity data changed");fake.attack(target);check(target.isAlive(),"reserved entity attacked");check(fake.interactOn(target,InteractionHand.MAIN_HAND)==net.minecraft.world.InteractionResult.FAIL,"reserved entity interacted");}}finally{CartAuditContext.restoring(true);}locked=true;}
                        if(done)break;
                    }
                    if(mode.endsWith("conflict"))check(rejected,"deployer conflict accepted");
                    else if(canceled){check(CreateDeployerTransaction.hand(context,fake).equals(a.getCompound("Hand")),"deployer compensation lost hand");for(var u:plan.blocks())check(CreateStructuralAudit.world(world,BlockPos.of(u.after().getLong("Pos"))).equals(u.after()),"deployer compensation lost world");
                        for(var u:plan.targets()){var expected=u.after();var live=world.getEntity(expected.getUUID("Entity"));check(expected.getBoolean("Terminal")?mode.contains("removed")?live==null||live.isRemoved():live instanceof LivingEntity&&!live.isAlive():expected.getBoolean("Exists")?live!=null&&!live.isRemoved()&&CreateLivingRuntime.entitySnapshot(world,live).equals(expected.getCompound("NBT")):live==null||live.isRemoved(),"deployer compensation lost entity/drop");
                            if(expected.contains("LivingRuntime",10))check(live instanceof LivingEntity living&&CreateLivingRuntime.matches(living,fake,expected.getCompound("LivingRuntime")),"deployer compensation lost combat runtime");}}
                    else{check(field(type,"committed").getBoolean(job),"deployer transaction not committed");check(CreateDeployerTransaction.hand(context,fake).equals(before),"deployer undo lost hand/components");for(var u:plan.blocks())check(CreateStructuralAudit.world(world,BlockPos.of(u.before().getLong("Pos"))).equals(u.before()),"deployer undo lost world");if(mode.startsWith("name"))check(!target.hasCustomName(),"name target not undone");if(mode.startsWith("frame")&&!mode.startsWith("frame-break"))check(((ItemFrame)target).getItem().isEmpty(),"frame target not undone");if(mode.equals("locks")||mode.equals("name-locks")||mode.equals("feed-locks"))check(locked,"deployer locks not tested");
                        if(mode.equals("name-move"))check(target.position().equals(pose),"data-only undo teleported moving target");
                        if(mode.startsWith("shear"))check(!((Sheep)target).isSheared(),"native shear not undone");
                        if(mode.equals("feed-natural-ticks")){var now=CreateLivingRuntime.entitySnapshot(world,target);for(String key:List.of("NeoForgeData","neoforge:attachments"))check(Objects.equals(now.get(key),recipientLive.get(key)),"undo overwrote unrelated player metadata: "+key);}
                        if(mode.startsWith("consume-fd/")||mode.startsWith("finish-fd/"))check(fake.getHealth()==12&&fake.getRemainingFireTicks()==100&&(!(mode.contains("milk_bottle")||mode.contains("hot_cocoa"))||fake.hasEffect(MobEffects.POISON)),"FD self consumption was not undone: "+mode+" health="+fake.getHealth()+" fire="+fake.getRemainingFireTicks()+" poison="+fake.hasEffect(MobEffects.POISON));
                        if(mode.startsWith("feed-fd/"))check(((LivingEntity)target).getHealth()==12&&target.getRemainingFireTicks()==100&&(!(mode.contains("milk_bottle")||mode.contains("hot_cocoa"))||((LivingEntity)target).hasEffect(MobEffects.POISON)),"FD heal/fire/cure was not undone");
                        if(mode.startsWith("feed-drink"))check(((LivingEntity)target).hasEffect(MobEffects.POISON),"recipient drink did not restore previous effect");
                        if(mode.contains("backpack-")&&mode.endsWith("locks"))check(locked,"external locks not tested");
                        if(mode.startsWith("backpack-inventory-"))check(((com.mrcrayfish.backpacked.inventory.BackpackedInventoryAccess)fake).backpacked$GetBackpackInventory(0).getItem(0).getCount()==2,"native backpack cache retained consumed contents");
                        if(mode.startsWith("feed"))check(CreateHumanRestore.capture((net.minecraft.server.level.ServerPlayer)target).equals(humanBefore)&&((net.minecraft.server.level.ServerPlayer)target).getFoodData().getFoodLevel()==7,"recipient feed was not undone");
                        if(mode.startsWith("combat")&&!nonlethal)check(world.getEntity(target.getUUID()) instanceof LivingEntity living&&living.isAlive()&&living.getHealth()==1&&living==target,"lethal target or its native AI identity was not restored");
                        if(mode.startsWith("combat-cardboard"))check(!((LivingEntity)target).hasEffect(MobEffects.MOVEMENT_SLOWDOWN)&&target.getDeltaMovement().equals(Vec3.ZERO),"cardboard motion/effect not undone");
                        if(mode.startsWith("consume-loose"))check(world.getEntity(target.getUUID()) instanceof net.minecraft.world.entity.item.ItemEntity item&&item.getItem().getCount()==2&&fake.getInventory().countItem(AllItems.POLISHED_ROSE_QUARTZ.get())==0,"loose world input was not returned");
                        if(nonlethal)check(((LivingEntity)target).getHealth()==8&&((LivingEntity)target).getLastHurtByMob()==null&&((LivingEntity)target).getLastHurtMob()==null,"combat health/references were not undone");
                        if(mode.startsWith("consume-polish"))check(fake.getOffhandItem().getCount()==2&&fake.getInventory().countItem(AllItems.POLISHED_ROSE_QUARTZ.get())==0&&fake.getFoodData().getFoodLevel()==7&&fake.getCooldowns().isOnCooldown(Items.ENDER_PEARL),"consumption runtime was not undone");
                        if(mode.startsWith("frame-break"))check(world.getEntity(target.getUUID()) instanceof ItemFrame,"removed frame UUID not restored");
                        for(int slot=0;slot<items.getSlots();slot++)check(ItemStack.matches(cargoBefore.get(slot),items.getStackInSlot(slot)),"deployer undo lost mounted cargo");}
                }finally{CartAuditContext.restoring(prior);CartAuditContext.rollbackActive=false;CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();}
                tested++;
            }finally{boolean prior=CartAuditContext.restoring(true);try{if(row!=null){var a=NbtSerde.fromSnbt(row.beAfter);if(a!=null)for(var raw:a.getList("Targets",10)){var t=(CompoundTag)raw;var e=world.getEntity(t.getUUID("Entity"));if(e!=null&&!e.isRemoved())e.discard();}}if(target!=null&&!target.isRemoved())target.discard();((CartEntityAccess)body).avilixlogger$discardForRollback();for(var p:List.of(pos,pos.below(),pos.west(),pos.east()))world.setBlock(p,Blocks.AIR.defaultBlockState(),3);}finally{CartAuditContext.restoring(prior);CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();}}
        }
        System.out.println("CREATE_DEPLOYER_JOINT_NATIVE_OK: "+tested+" scenarios; four construction kinds, native block/bucket/lever/name/frame, nonlethal combat and sandpaper consumption, runtime restoration/compensation, early conflicts, locks, no-op and failure barriers; lethal corpse/recreated target, scoreboard/team restoration, recipient inventory/statistics/advancements, real-level loose input, Create food/tea/cardboard and optional native FD food/effects/containers");
    }
    private static void backpackFixture(net.minecraft.world.entity.player.Player player,int count){
        var bag=com.mrcrayfish.backpacked.core.ModSyncedDataKeys.BACKPACKS.getValue(player).get(0);if(bag.isEmpty())bag=new ItemStack(com.mrcrayfish.backpacked.core.ModItems.BACKPACK.get());
        bag.set(com.mrcrayfish.backpacked.core.ModDataComponents.UNLOCKABLE_SLOTS.get(),com.mrcrayfish.backpacked.common.backpack.UnlockableSlots.ALL);
        com.mrcrayfish.backpacked.core.ModSyncedDataKeys.BACKPACKS.getValue(player).set(0,bag);
        var nativeInventory=((com.mrcrayfish.backpacked.inventory.BackpackedInventoryAccess)player).backpacked$GetBackpackInventory(0);
        nativeInventory.setItem(0,new ItemStack(Items.DIAMOND,count));nativeInventory.saveItemsToStack();
    }
    private static void runtimeRoundTrip(ServerLevel world){
        var fake=new DeployerFakePlayer(world,UUID.randomUUID());fake.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.MILK_BUCKET));
        check(CreateLivingRuntime.reference(CreateLivingRuntime.reference(fake,fake),fake)==fake,"technical actor resolved by owner's UUID instead of exact context");
        var otherFake=new DeployerFakePlayer(world,fake.getUUID());reject(()->CreateLivingRuntime.reference(otherFake,fake),"another fake with the same owner was accepted as this actor");
        fake.getActiveEffectsMap().put(MobEffects.POISON,new MobEffectInstance(MobEffects.POISON,200));fake.getFoodData().setFoodLevel(7);fake.getFoodData().setSaturation(2);
        fake.getCooldowns().addCooldown(Items.ENDER_PEARL,40);var access=(CreateDeployerRuntimeAccess)fake;var before=access.avilixlogger$deployerRuntime();
        var bindings=CreateLivingRuntime.bind(fake,List.of(before.getCompound("Living")));
        fake.setItemInHand(InteractionHand.MAIN_HAND,fake.getMainHandItem().finishUsingItem(world,fake));fake.getFoodData().setFoodLevel(11);fake.experienceLevel=5;
        fake.getCooldowns().tick();fake.getCooldowns().addCooldown(Items.SHIELD,20);check(!fake.hasEffect(MobEffects.POISON),"native milk did not clear effect fixture");
        bindings.run(()->access.avilixlogger$deployerRuntime(before));
        var restoredRuntime=access.avilixlogger$deployerRuntime();
        check(restoredRuntime.equals(before),"runtime round trip: "+differences("",before,restoredRuntime));
        var sheep=EntityType.SHEEP.create(world);var health=sheep.getAttribute(Attributes.MAX_HEALTH);
        health.addTransientModifier(new AttributeModifier(ResourceLocation.fromNamespaceAndPath("avilixlogger","smoke_health"),32,AttributeModifier.Operation.ADD_VALUE));sheep.setHealth(35);
        var healthy=CreateLivingRuntime.capture(sheep,fake);health.removeModifiers();sheep.setHealth(4);
        CreateLivingRuntime.bind(fake,List.of(healthy)).run(()->CreateLivingRuntime.restore(sheep,fake,healthy));
        check(sheep.getHealth()==35&&CreateLivingRuntime.matches(sheep,fake,healthy),"transient max-health restore clamped saved health");
        var expandedHand=new ItemStack(Items.APPLE,1024);expandedHand.set(DataComponents.CUSTOM_NAME,Component.literal("runtime expanded hand"));
        fake.setItemInHand(InteractionHand.MAIN_HAND,expandedHand);
        fake.getInventory().setItem(36,new ItemStack(Items.IRON_BOOTS,200));
        fake.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.APPLE,2048));
        fake.startUsingItem(InteractionHand.MAIN_HAND);
        var using=CreateLivingRuntime.capture(fake,fake);
        check(using.getCompound("UseItem").getInt("count")==1024,"active item lost expanded count during capture");
        check(fake.getMainHandItem()==expandedHand&&expandedHand.getCount()==1024,"capture mutated the live expanded hand");
        fake.stopUsingItem();
        fake.getInventory().clearContent();
        CreateLivingRuntime.bind(fake,List.of(using)).run(()->CreateLivingRuntime.restore(fake,fake,using));
        check(fake.getUseItem().getCount()==1024&&fake.getMainHandItem().getCount()==1024
                &&fake.getMainHandItem().has(DataComponents.CUSTOM_NAME)&&fake.getInventory().getItem(36).getCount()==200
                &&fake.getOffhandItem().getCount()==2048&&CreateLivingRuntime.matches(fake,fake,using),
                "expanded active item, armor or offhand was not restored exactly");
    }
    private static Method method(Class<?> type,String name,Class<?>...args)throws Exception{var m=type.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    private static String differences(String path,CompoundTag expected,CompoundTag actual){
        var keys=new TreeSet<>(expected.getAllKeys());keys.addAll(actual.getAllKeys());var out=new ArrayList<String>();
        for(String key:keys){var before=expected.get(key);var after=actual.get(key);if(Objects.equals(before,after))continue;
            out.add(before instanceof CompoundTag b&&after instanceof CompoundTag a?differences(path+key+".",b,a):path+key+": "+before+" -> "+after);}
        return String.join("; ",out);
    }
    private static Field field(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void reject(Runnable action,String message){try{action.run();}catch(RuntimeException expected){return;}throw new AssertionError(message);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

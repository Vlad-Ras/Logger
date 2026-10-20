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
        runtimeRoundTrip(world);
        var factory=method(CreateCargoSmoke.class,"entity",ServerLevel.class,CreateContraptionKind.class);var attach=method(CreateLinkedTransferSmoke.class,"attachCarriage",AbstractContraptionEntity.class);
        var pos=world.getSharedSpawnPos().offset(40,40,40);method(CreateFunnelSmoke.class,"visible",ServerLevel.class,BlockPos.class).invoke(null,world,pos);
        int tested=0;
        for(var kind:List.of(CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER))
        for(String mode:List.of("place","place-cancel-first","place-cancel-all","water","lever","name","name-cancel-all","frame","frame-cancel-all","hand-conflict","world-conflict","entity-conflict","locks","failure","noop","combat","combat-live","combat-live-cancel-first","combat-live-cancel-all","combat-live-conflict","consume-polish","consume-polish-cancel-all","consume-polish-conflict","name-move","name-locks","shear","shear-cancel-all","frame-break","frame-break-cancel-all","cargo-grab","cargo-return","cargo-conflict","cargo-cancel-all","place-oversized")){
            var body=(AbstractContraptionEntity)factory.invoke(null,world,kind);attach.invoke(null,body);body.setPos(pos.above(5).getX(),pos.above(5).getY(),pos.above(5).getZ());check(world.addFreshEntity(body),"deployer construction spawn");Entity target=null;LogEntry row=null;
            try{
                world.setBlock(pos.below(),Blocks.STONE.defaultBlockState(),3);world.setBlock(pos,mode.equals("lever")?Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE,net.minecraft.world.level.block.state.properties.AttachFace.FLOOR):Blocks.AIR.defaultBlockState(),3);
                boolean punch=mode.startsWith("combat")||mode.startsWith("frame-break");
                var actorPos=new BlockPos(7,0,0);var settings=new CompoundTag();settings.putUUID("Owner",UUID.randomUUID());settings.putString("Mode",punch?"PUNCH":"USE");
                var info=new StructureBlockInfo(actorPos,AllBlocks.DEPLOYER.getDefaultState().setValue(DeployerBlock.FACING,Direction.EAST),settings);var context=new MovementContext(world,info,body.getContraption());context.position=Vec3.atCenterOf(pos);body.getContraption().getActors().add(MutablePair.of(info,context));
                var fake=new DeployerFakePlayer(world,settings.getUUID("Owner"));context.temporaryData=fake;fake.getInventory().selected=1;
                ItemStack held=new ItemStack(mode.equals("water")?Items.WATER_BUCKET:mode.startsWith("name")||mode.equals("entity-conflict")?Items.NAME_TAG:mode.startsWith("shear")?Items.SHEARS:mode.startsWith("frame")?Items.DIAMOND:mode.equals("combat")?Items.DIAMOND_SWORD:mode.equals("lever")||mode.equals("noop")?Items.AIR:Items.STONE);
                if(mode.startsWith("combat-live"))held=new ItemStack(Items.WOODEN_SWORD);
                if(mode.startsWith("consume-polish")){
                    held=new ItemStack(AllItems.SAND_PAPER.get());fake.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(AllItems.ROSE_QUARTZ.get(),2));
                    fake.getFoodData().setFoodLevel(7);fake.getFoodData().setSaturation(2);
                    fake.getCooldowns().addCooldown(Items.ENDER_PEARL,40);
                }
                if(mode.equals("place-oversized")){held.setCount(1024);held.set(DataComponents.CUSTOM_NAME,Component.literal("large hand component"));}
                if(held.is(Items.NAME_TAG))held.set(DataComponents.CUSTOM_NAME,Component.literal("deployer named target"));fake.setItemInHand(InteractionHand.MAIN_HAND,held);fake.getInventory().setItem(37,new ItemStack(Items.IRON_CHESTPLATE));
                if(mode.startsWith("name")||mode.startsWith("shear")||mode.equals("entity-conflict")||mode.startsWith("combat")){var sheep=EntityType.SHEEP.create(world);sheep.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);check(world.addFreshEntity(sheep),"target sheep spawn");target=sheep;}
                if(mode.startsWith("frame")){world.setBlock(pos.west(),Blocks.STONE.defaultBlockState(),3);var frame=new ItemFrame(world,pos,Direction.EAST);check(world.addFreshEntity(frame),"target frame spawn");target=frame;}
                var items=body.getContraption().getStorage().getAllItemStorages().get(BlockPos.ZERO);
                items.setStackInSlot(0,new ItemStack(Items.DIAMOND,7));
                if(mode.equals("cargo-grab")||mode.equals("cargo-conflict"))fake.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                var cargoBefore=new ArrayList<ItemStack>();for(int slot=0;slot<items.getSlots();slot++)cargoBefore.add(items.getStackInSlot(slot).copy());
                var before=CreateDeployerTransaction.hand(context,fake);
                try(var session=CreateDeployerAudit.capture(context,pos,fake,punch?"PUNCH":"USE")){
                    if(mode.startsWith("cargo")){
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
                    row=session.finish(!mode.equals("failure"));
                }
                if(mode.equals("noop")){check(row==null,"idle/no-op activation invented a row");tested++;continue;}
                check(row!=null,"native deployer action not captured: "+kind+"/"+mode);row.materializeSnapshots();var b=NbtSerde.fromSnbt(row.beBefore);var a=NbtSerde.fromSnbt(row.beAfter);
                check(row.actorUuid==null&&"SYSTEM".equals(row.actorName),"deployer blamed its owner");check(CreateContraptionKind.id(row.source).equals(CreateContraptionSources.id(body)),"deployer root ID lost");
                if(mode.equals("failure")||mode.equals("combat")){check(!a.getBoolean("Complete"),"partial/unsupported attack enabled undo");reject(()->CreateTransferRollbackPlan.prepare(b,a),"partial/unsupported deployer accepted");tested++;continue;}
                var plan=CreateTransferRollbackPlan.prepare(b,a);
                if(mode.startsWith("combat-live"))check(target.isAlive()&&((LivingEntity)target).getHealth()<8&&a.getInt("JointFormat")>=3,"nonlethal combat runtime was not captured");
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
                if(mode.equals("combat-live-conflict"))((LivingEntity)target).setHealth(1);
                if(mode.equals("consume-polish-conflict"))fake.getFoodData().setFoodLevel(9);
                if(mode.equals("world-conflict"))world.setBlock(pos,Blocks.GOLD_BLOCK.defaultBlockState(),3);
                if(mode.equals("entity-conflict"))target.setCustomName(Component.literal("later target change"));
                var type=Class.forName("com.roften.avilixlogger.compat.create.CreateTransferRollbackCoordinator$Job");var ctor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CreateTransferRollbackPlan.class,long.class);ctor.setAccessible(true);var job=ctor.newInstance(world.getServer().createCommandSourceStack(),plan,1L);var step=method(type,"step",net.minecraft.server.MinecraftServer.class);var cancel=method(type,"cancel",String.class);var applied=field(type,"applied");
                boolean canceled=false,rejected=false,locked=false;boolean prior=CartAuditContext.restoring(true);CartAuditContext.rollbackActive=true;
                try{
                    for(int calls=0;;calls++){
                        check(calls<1000,"deployer rollback did not terminate");boolean done;
                        try{done=(Boolean)step.invoke(job,world.getServer());}catch(InvocationTargetException e){if(!mode.endsWith("conflict"))throw new IllegalStateException(kind+"/"+mode,e.getCause());check(applied.getInt(job)==0,"deployer preflight conflict wrote hand");rejected=true;break;}
                        int total=plan.cargo().size()+plan.hands().size()+plan.blocks().size()+plan.targets().size();
                        if(mode.contains("cancel")&&!canceled&&applied.getInt(job)>=(mode.endsWith("all")?total:1)){cancel.invoke(job,"deployer partial cancellation");canceled=true;}
                        if((mode.equals("locks")||mode.equals("name-locks"))&&!locked&&applied.getInt(job)>0){CartAuditContext.restoring(false);try{fake.getInventory().setItem(1,new ItemStack(Items.EMERALD));check(!fake.getMainHandItem().is(Items.EMERALD),"reserved fake inventory changed");if(mode.equals("locks"))check(!world.setBlock(pos,Blocks.GOLD_BLOCK.defaultBlockState(),3),"reserved deployer target changed");
                            if(target!=null){target.discard();check(!target.isRemoved(),"reserved entity removed");
                            int tickBefore=target.tickCount;world.tickNonPassenger(target);check(target.tickCount==tickBefore,"reserved entity tick advanced between restore steps");
                            check(!target.hurt(world.damageSources().generic(),100),"reserved entity accepted environmental damage");
                            var named=target.getCustomName();target.setCustomName(Component.literal("external reserved write"));check(Objects.equals(named,target.getCustomName()),"reserved entity data changed");fake.attack(target);check(target.isAlive(),"reserved entity attacked");check(fake.interactOn(target,InteractionHand.MAIN_HAND)==net.minecraft.world.InteractionResult.FAIL,"reserved entity interacted");}}finally{CartAuditContext.restoring(true);}locked=true;}
                        if(done)break;
                    }
                    if(mode.endsWith("conflict"))check(rejected,"deployer conflict accepted");
                    else if(canceled){check(CreateDeployerTransaction.hand(context,fake).equals(a.getCompound("Hand")),"deployer compensation lost hand");for(var u:plan.blocks())check(CreateStructuralAudit.world(world,BlockPos.of(u.after().getLong("Pos"))).equals(u.after()),"deployer compensation lost world");
                        for(var u:plan.targets()){var expected=u.after();var live=world.getEntity(expected.getUUID("Entity"));check(expected.getBoolean("Exists")?live!=null&&!live.isRemoved()&&NbtSerde.snapshotEntity(world,live).equals(expected.getCompound("NBT")):live==null||live.isRemoved(),"deployer compensation lost entity/drop");
                            if(expected.contains("LivingRuntime",10))check(live instanceof LivingEntity living&&CreateLivingRuntime.matches(living,fake,expected.getCompound("LivingRuntime")),"deployer compensation lost combat runtime");}}
                    else{check(field(type,"committed").getBoolean(job),"deployer transaction not committed");check(CreateDeployerTransaction.hand(context,fake).equals(before),"deployer undo lost hand/components");for(var u:plan.blocks())check(CreateStructuralAudit.world(world,BlockPos.of(u.before().getLong("Pos"))).equals(u.before()),"deployer undo lost world");if(mode.startsWith("name"))check(!target.hasCustomName(),"name target not undone");if(mode.startsWith("frame")&&!mode.startsWith("frame-break"))check(((ItemFrame)target).getItem().isEmpty(),"frame target not undone");if(mode.equals("locks")||mode.equals("name-locks"))check(locked,"deployer locks not tested");
                        if(mode.equals("name-move"))check(target.position().equals(pose),"data-only undo teleported moving target");
                        if(mode.startsWith("shear"))check(!((Sheep)target).isSheared(),"native shear not undone");
                        if(mode.startsWith("combat-live"))check(((LivingEntity)target).getHealth()==8&&((LivingEntity)target).getLastHurtByMob()==null&&((LivingEntity)target).getLastHurtMob()==null,"combat health/references were not undone");
                        if(mode.startsWith("consume-polish"))check(fake.getOffhandItem().getCount()==2&&fake.getInventory().countItem(AllItems.POLISHED_ROSE_QUARTZ.get())==0&&fake.getFoodData().getFoodLevel()==7&&fake.getCooldowns().isOnCooldown(Items.ENDER_PEARL),"consumption runtime was not undone");
                        if(mode.startsWith("frame-break"))check(world.getEntity(target.getUUID()) instanceof ItemFrame,"removed frame UUID not restored");
                        for(int slot=0;slot<items.getSlots();slot++)check(ItemStack.matches(cargoBefore.get(slot),items.getStackInSlot(slot)),"deployer undo lost mounted cargo");}
                }finally{CartAuditContext.restoring(prior);CartAuditContext.rollbackActive=false;CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();}
                tested++;
            }finally{boolean prior=CartAuditContext.restoring(true);try{if(row!=null){var a=NbtSerde.fromSnbt(row.beAfter);if(a!=null)for(var raw:a.getList("Targets",10)){var t=(CompoundTag)raw;var e=world.getEntity(t.getUUID("Entity"));if(e!=null&&!e.isRemoved())e.discard();}}if(target!=null&&!target.isRemoved())target.discard();((CartEntityAccess)body).avilixlogger$discardForRollback();for(var p:List.of(pos,pos.below(),pos.west()))world.setBlock(p,Blocks.AIR.defaultBlockState(),3);}finally{CartAuditContext.restoring(prior);CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();}}
        }
        System.out.println("CREATE_DEPLOYER_JOINT_NATIVE_OK: "+tested+" scenarios; four construction kinds, native block/bucket/lever/name/frame, nonlethal combat and sandpaper consumption, runtime restoration/compensation, early conflicts, locks, no-op, failure and lethal combat rejection");
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
        check(access.avilixlogger$deployerRuntime().equals(before),"runtime round trip lost effects, food, XP, inventory or cooldown phase");
        var sheep=EntityType.SHEEP.create(world);var health=sheep.getAttribute(Attributes.MAX_HEALTH);
        health.addTransientModifier(new AttributeModifier(ResourceLocation.fromNamespaceAndPath("avilixlogger","smoke_health"),32,AttributeModifier.Operation.ADD_VALUE));sheep.setHealth(35);
        var healthy=CreateLivingRuntime.capture(sheep,fake);health.removeModifiers();sheep.setHealth(4);
        CreateLivingRuntime.bind(fake,List.of(healthy)).run(()->CreateLivingRuntime.restore(sheep,fake,healthy));
        check(sheep.getHealth()==35&&CreateLivingRuntime.matches(sheep,fake,healthy),"transient max-health restore clamped saved health");
        fake.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.APPLE,1024));
        fake.startUsingItem(InteractionHand.MAIN_HAND);
        var using=CreateLivingRuntime.capture(fake,fake);
        check(using.getCompound("UseItem").getInt("count")==1024,"active item lost expanded count during capture");
        fake.stopUsingItem();
        CreateLivingRuntime.bind(fake,List.of(using)).run(()->CreateLivingRuntime.restore(fake,fake,using));
        check(fake.getUseItem().getCount()==1024&&CreateLivingRuntime.matches(fake,fake,using),
                "expanded active item was not restored exactly");
    }
    private static Method method(Class<?> type,String name,Class<?>...args)throws Exception{var m=type.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    private static Field field(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void reject(Runnable action,String message){try{action.run();}catch(RuntimeException expected){return;}throw new AssertionError(message);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.*;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.trains.entity.*;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import java.util.UUID;

public final class CreateCompletionSmoke {
    public static void run(ServerLevel world) throws Exception {
        check(CreateSupport.available(),CreateSupport.diagnostic());
        actorEffects(world);
        CreateFunnelSmoke.run(world);
        CreateLinkedTransferSmoke.run(world);
        CreateTransferWorldSmoke.run(world);
        CreateLifecycleSmoke.run(world);
        CreateStructuralSmoke.run(world);
        CreateCouplingRollbackSmoke.run(world);
        CreateCoupledStructuralSmoke.run(world);
        CreateTrainRollbackSmoke.run(world);
        CreateDeployerJointSmoke.run(world);
        CreateDeployerRecipientSmoke.run(world);
        CreateCreativeTransferSmoke.run(world);
        CreateWorldCreativeSmoke.run(world);
        CreateDeployerCreativeSmoke.run(world);
        delayedDrops(world);
        int tested=0;
        for(var kind:new CreateContraptionKind[]{CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER}) {
            for(boolean known:new boolean[]{false,true}) {
                UUID owner=known?UUID.randomUUID():null;
                String source=kind.source(UUID.randomUUID(),"shot");
                var arrow=new Arrow(EntityType.ARROW,world);
                var technical=net.neoforged.neoforge.common.util.FakePlayerFactory.get(world,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"delayed_create"));
                arrow.setOwner(technical);
                var old=MutationSourceResolver.enter(source);
                var oldMechanism=CreateMechanismContext.enter(new CreateMechanismContext.Stamp("create:dispenser",1,2,3));
                var oldActor=CreateActorContext.enter(new CreateActorContext.Stamp(owner,known?"Owner":null,technical));
                try { check(world.addFreshEntity(arrow),"arrow spawn"); }
                finally { CreateActorContext.restore(oldActor);CreateMechanismContext.restore(oldMechanism);MutationSourceResolver.restore(old); }
                String expected=source+":mechanism=create:dispenser@1,2,3";
                var origin=CreateEntityOrigin.read(arrow);
                check(origin!=null && expected.equals(origin.source()) && java.util.Objects.equals(owner,origin.owner()),"source lost after callback");
                check(expected.equals(MutationSourceResolver.sourceFor(arrow)),"delayed tick source");
                var saved=NbtSerde.snapshotEntity(world,arrow);
                var loaded=(Arrow)EntityType.loadEntityRecursive(saved,world,e->e);
                check(loaded!=null && origin.equals(CreateEntityOrigin.read(loaded)),"entity origin reload");
                var actorMethod=LoggerEventHandlers.class.getDeclaredMethod("projectileActor",ServerLevel.class,net.minecraft.world.entity.projectile.Projectile.class);actorMethod.setAccessible(true);
                var actor=actorMethod.invoke(null,world,loaded);
                check(actor==null && origin.actor()==null,"saved mechanism ownership became a delayed human actor");
                try(var scope=CreateEntityOrigin.cause(loaded)){check(CauseContext.peek()==null,"delayed cause");}
                check(CauseContext.peek()==null,"delayed cause leak");
                arrow.discard();loaded.discard();tested+=5;
            }
        }
        var resolveActor=LoggerEventHandlers.class.getDeclaredMethod("resolveActorForEntity",ServerLevel.class,net.minecraft.world.entity.Entity.class,CompoundTag.class);resolveActor.setAccessible(true);
        var victim=new net.minecraft.world.entity.decoration.ArmorStand(world,0,80,0);
        var guessed=UUID.randomUUID();ActorTracker.note(victim.getUUID(),guessed,"Nearby");
        var previous=MutationSourceResolver.enter(CreateContraptionKind.BEARING.source(UUID.randomUUID(),"delayed"));
        try {var responsible=(ActorTracker.ActorRef)resolveActor.invoke(null,world,victim,new CompoundTag());check(responsible==null,"nearby actor overrode known construction");tested++;}
        finally{MutationSourceResolver.restore(previous);}
        var pos=world.getSharedSpawnPos().above(25);
        world.setBlock(pos,Blocks.CHEST.defaultBlockState(),3);
        IControlContraption control=new IControlContraption(){
            public boolean isAttachedTo(AbstractContraptionEntity entity){return true;}
            public void attach(ControlledContraptionEntity entity){}
            public void onStall(){}
            public boolean isValid(){return true;}
            public BlockPos getBlockPosition(){return pos;}
        };
        try {
            var first=ControlledContraptionEntity.create(world,control,bearing(pos.above()));
            UUID id=CreateContraptionSources.id(first);
            var saved=first.getContraption().writeNBT(world.registryAccess(),false);
            var restored=Contraption.fromNBT(world,saved,false);
            check(id.equals(((ContraptionIdentity)restored).avilixlogger$identity()),"contraption reload");
            var next=ControlledContraptionEntity.create(world,control,bearing(pos.above()));
            check(id.equals(CreateContraptionSources.id(next)),"reassembly history lost");
            var secondary=ControlledContraptionEntity.create(world,control,bearing(pos.above(2)));
            check(!id.equals(CreateContraptionSources.id(secondary)),"controller conflated two constructions");
            var marker=world.getBlockEntity(pos).getPersistentData().copy();
            world.setBlock(pos,Blocks.AIR.defaultBlockState(),3);world.setBlock(pos,Blocks.CHEST.defaultBlockState(),3);
            var replacement=ControlledContraptionEntity.create(world,control,bearing(pos.above()));
            check(!id.equals(CreateContraptionSources.id(replacement)),"replacement controller reused destroyed identity");
            marker.getCompound("AvilixControllerIdentities").putString("Address","other-dimension@copied");
            world.getBlockEntity(pos).getPersistentData().merge(marker);
            var copied=ControlledContraptionEntity.create(world,control,bearing(pos.above()));
            check(!id.equals(CreateContraptionSources.id(copied)),"copied controller reused source identity");
            tested+=5;
        } finally { world.setBlock(pos,Blocks.AIR.defaultBlockState(),3); }
        var carriage=new Carriage(new CarriageBogey(AllBlocks.SMALL_BOGEY.get(),false,new CompoundTag()),null,0);
        var identity=(ContraptionIdentity)carriage;var carriageId=identity.avilixlogger$identity();
        var dimensions=new DimensionPalette();var tag=carriage.write(dimensions,world.registryAccess());
        var loadedCarriage=Carriage.read(tag,world.registryAccess(),null,dimensions);
        check(carriageId.equals(((ContraptionIdentity)loadedCarriage).avilixlogger$identity()),"carriage identity reload");
        check(!carriageId.equals(((ContraptionIdentity)new Carriage(new CarriageBogey(AllBlocks.SMALL_BOGEY.get(),false,new CompoundTag()),null,0)).avilixlogger$identity()),"carriages share ID");
        tested+=2;
        System.out.println("CREATE_COMPLETION_IDENTITY_ORIGIN_NATIVE_OK: "+tested+" assertions; delayed owner/system + save/load + controller cycles/copies + carriage persistence");
    }
    private static void actorEffects(ServerLevel world) throws Exception {
        var factory=CreateCargoSmoke.class.getDeclaredMethod("entity",ServerLevel.class,CreateContraptionKind.class);factory.setAccessible(true);
        var local=new BlockPos(30,0,0);var target=world.getSharedSpawnPos().above(27);
        int count=0;
        for(var kind:new CreateContraptionKind[]{CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER}) {
            var entity=(AbstractContraptionEntity)factory.invoke(null,world,kind);var c=entity.getContraption();
            c.getStorage().initialize();CartStorageAudit.bind(entity);
            var chest=c.getStorage().getAllItemStorages().get(BlockPos.ZERO);
            var basinTag=new CompoundTag();
            var input=new net.neoforged.neoforge.items.ItemStackHandler(9);input.setStackInSlot(4,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,5));
            var output=new net.neoforged.neoforge.items.ItemStackHandler(8);output.setStackInSlot(2,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD,3));
            basinTag.put("InputItems",input.serializeNBT(world.registryAccess()));basinTag.put("OutputItems",output.serializeNBT(world.registryAccess()));
            var info=new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(local,AllBlocks.BASIN.getDefaultState(),basinTag);c.getBlocks().put(local,info);
            var context=new com.simibubi.create.content.contraptions.behaviour.MovementContext(world,info,c);context.position=net.minecraft.world.phys.Vec3.atCenterOf(target);
            var dump=com.simibubi.create.content.processing.basin.BasinMovementBehaviour.class.getDeclaredMethod("dump",com.simibubi.create.content.contraptions.behaviour.MovementContext.class,net.minecraft.world.phys.Vec3.class);dump.setAccessible(true);
            try(var capture=CreateCargoAudit.captureChanges(entity)) {
                dump.invoke(new com.simibubi.create.content.processing.basin.BasinMovementBehaviour(),context,new net.minecraft.world.phys.Vec3(0,-1,0));
                var rows=capture.rows();check(rows.stream().filter(r->r.type==ActionType.ENTITY_SPAWN).count()==2,"basin drops missing");
                check(rows.stream().filter(r->r.type==ActionType.CART_STRUCTURE_CHANGE).count()==1,"basin direct NBT mutation missing");
                for(var row:rows){row.materializeSnapshots();check(CreateContraptionKind.id(row.source).equals(CreateContraptionSources.id(entity)),"actor source ID");
                    if(row.type==ActionType.ENTITY_SPAWN){var dropped=world.getEntity(row.entityUuid);check(dropped instanceof net.minecraft.world.entity.item.ItemEntity,"basin native drop missing");
                        var item=(net.minecraft.world.entity.item.ItemEntity)dropped;item.setNoPickUpDelay();
                        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(world,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"basin_pickup"));
                        var constructor=CartLootTransferAudit.Scope.class.getDeclaredConstructor(net.minecraft.world.entity.item.ItemEntity.class,net.minecraft.server.level.ServerPlayer.class);constructor.setAccessible(true);
                        var pickup=(CartLootTransferAudit.Scope)constructor.newInstance(item,player);item.playerTouch(player);var picked=pickup.finish(true);check(picked!=null && CreateContraptionKind.fromSource(picked.source)==kind,"pickup lost construction kind");picked.materializeSnapshots();check(NbtSerde.fromSnbt(picked.playerInvAfter).getBoolean("Complete"),"actor drop pickup incomplete");
                        dropped.discard();}
                    else {var before=NbtSerde.fromSnbt(row.beBefore);var after=NbtSerde.fromSnbt(row.beAfter);check(!before.getCompound("Data").equals(after.getCompound("Data")),"basin before/after data aliased");}
                }
                check(Boolean.FALSE.equals(context.temporaryData),"audit overwrote basin state");count++;
            }
            try(var capture=CreateCargoAudit.captureChanges(entity)) {
                try(var failure=CreateActorEffectsAudit.begin(context,"injected_actor_failure",false,true)){context.blockEntityData.putInt("Fault",1);}
                check(capture.rows().stream().anyMatch(r->r.type==ActionType.CART_TRANSFER),"partial actor failure was not recorded");count++;
            }
            var sawInfo=new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(local,AllBlocks.MECHANICAL_SAW.getDefaultState(),new CompoundTag());
            var saw=new com.simibubi.create.content.contraptions.behaviour.MovementContext(world,sawInfo,c);saw.position=net.minecraft.world.phys.Vec3.atCenterOf(target);
            try(var capture=CreateCargoAudit.captureChanges(entity)){
                new com.simibubi.create.content.kinetics.saw.SawMovementBehaviour().dropItemFromCutTree(saw,target,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG,5));
                check(capture.rows().size()==1,"saw collection missing or duplicated");var row=capture.rows().getFirst();row.materializeSnapshots();check(!NbtSerde.fromSnbt(row.beAfter).getBoolean("External"),"saw collection not bound to internal actor");count++;
            }
            var pavingTag=new CompoundTag();pavingTag.put("Filter",new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE).save(world.registryAccess()));
            var rollerInfo=new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(local,AllBlocks.MECHANICAL_ROLLER.getDefaultState(),pavingTag);
            var roller=new com.simibubi.create.content.contraptions.behaviour.MovementContext(world,rollerInfo,c);roller.position=net.minecraft.world.phys.Vec3.atCenterOf(target);
            chest.setStackInSlot(1,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE,3));
            var pave=java.lang.invoke.MethodHandles.privateLookupIn(com.simibubi.create.content.contraptions.actors.roller.RollerMovementBehaviour.class,java.lang.invoke.MethodHandles.lookup()).findVirtual(com.simibubi.create.content.contraptions.actors.roller.RollerMovementBehaviour.class,"triggerPaver",java.lang.invoke.MethodType.methodType(void.class,com.simibubi.create.content.contraptions.behaviour.MovementContext.class,BlockPos.class));
            try(var capture=CreateCargoAudit.captureChanges(entity)){
                world.setBlock(target,Blocks.AIR.defaultBlockState(),3);try{pave.invoke(new com.simibubi.create.content.contraptions.actors.roller.RollerMovementBehaviour(),roller,target);}catch(Throwable failure){throw new AssertionError("native paver failed",failure);}
                check(world.getBlockState(target).is(Blocks.STONE)&&chest.getStackInSlot(1).getCount()==2,"roller native paving");
                check(capture.rows().size()==1,"roller consumption missing or duplicated");capture.rows().getFirst().materializeSnapshots();check(!NbtSerde.fromSnbt(capture.rows().getFirst().beAfter).getBoolean("External"),"roller consumption not linked to world work");count++;
            } finally {world.setBlock(target,Blocks.AIR.defaultBlockState(),3);}
            var harvestInfo=new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(local,AllBlocks.MECHANICAL_HARVESTER.getDefaultState(),new CompoundTag());
            var harvest=new com.simibubi.create.content.contraptions.behaviour.MovementContext(world,harvestInfo,c);harvest.position=net.minecraft.world.phys.Vec3.atCenterOf(target);
            world.setBlock(target.below(),Blocks.FARMLAND.defaultBlockState(),3);world.setBlock(target,Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.CropBlock.AGE,7),3);
            try(var capture=CreateCargoAudit.captureChanges(entity)) {
                new com.simibubi.create.content.contraptions.actors.harvester.HarvesterMovementBehaviour().visitNewPosition(harvest,target);
                check(world.getBlockState(target).is(Blocks.WHEAT)&&world.getBlockState(target).getValue(net.minecraft.world.level.block.CropBlock.AGE)==0,"harvester native replant");
                check(!capture.rows().isEmpty(),"harvester cargo missing");for(var row:capture.rows()){row.materializeSnapshots();check(!NbtSerde.fromSnbt(row.beAfter).getBoolean("External"),"harvester loot not internal");}count++;
            } finally {world.setBlock(target,Blocks.AIR.defaultBlockState(),3);world.setBlock(target.below(),Blocks.AIR.defaultBlockState(),3);}
            var ploughInfo=new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(local,AllBlocks.MECHANICAL_PLOUGH.getDefaultState(),new CompoundTag());
            var plough=new com.simibubi.create.content.contraptions.behaviour.MovementContext(world,ploughInfo,c);plough.position=net.minecraft.world.phys.Vec3.atCenterOf(target);
            final int[] observed={0};
            java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockEvent.BlockToolModificationEvent> listener=event->{
                if(event.getLevel()!=world || !event.getPos().equals(target.below()))return;
                check(CreateActorContext.isTechnicalPlayer(event.getPlayer()),"plough fake player escaped mechanism context");
                check(CreateTrainsCompatHooks.actorUuid(event.getPlayer())==null,"unknown plough owner became fake player UUID");observed[0]++;
            };
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
            world.setBlock(target.below(),Blocks.DIRT.defaultBlockState(),3);
            try(var capture=CreateCargoAudit.captureChanges(entity)){
                new com.simibubi.create.content.contraptions.actors.plough.PloughMovementBehaviour().visitNewPosition(plough,target);
                check(world.getBlockState(target.below()).is(Blocks.FARMLAND)&&observed[0]>0,"plough native tilling");count++;
            }finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);world.setBlock(target.below(),Blocks.AIR.defaultBlockState(),3);}
            check(CreateActorContext.current()==null && CreateMechanismContext.current()==null,"actor effect scope leaked");
        }
        System.out.println("CREATE_ADDITIONAL_ACTORS_NATIVE_OK: "+count+" scenarios; native basin NBT/drops and typed pickup, partial failure, saw tree cargo, roller paving harvester replant and plough technical-player guard for four kinds");
    }
    private static BearingContraption bearing(BlockPos anchor){var c=new BearingContraption(false,Direction.UP);c.anchor=anchor;c.bounds=new AABB(BlockPos.ZERO);return c;}
    private static void delayedDrops(ServerLevel world) throws Exception {
        int tested=0;
        var ordinary=new net.minecraft.world.entity.item.ItemEntity(world,0,100,0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT));
        var peek=(CreateOriginDataAccess)ordinary;
        check(peek.avilixlogger$originData()==null,"ordinary item data allocated before origin lookup");
        check(CreateEntityOrigin.read(ordinary)==null,"ordinary item has a construction origin");
        var pickupSource=CartLootTransferAudit.class.getDeclaredMethod("source",net.minecraft.world.entity.item.ItemEntity.class);
        pickupSource.setAccessible(true);
        check(pickupSource.invoke(null,ordinary)==null&&peek.avilixlogger$originData()==null,
                "ordinary merge/pickup origin lookup allocated persistent NBT");
        MutationSourceResolver.sourceFor(ordinary);
        check(peek.avilixlogger$originData()==null,"origin lookup allocates NBT for every ordinary item");tested++;
        for(var kind:new CreateContraptionKind[]{CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER}) {
            var id=UUID.randomUUID();var owner=UUID.randomUUID();var source=kind.source(id,"delayed_effect");
            var item=new net.minecraft.world.entity.item.ItemEntity(world,0,100,0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,3));
            var previous=MutationSourceResolver.enter(source);
            try(var cause=CauseContext.push(new CauseContext.Cause(owner,"DelayedOwner",CauseContext.Kind.OTHER,item.blockPosition(),null,System.currentTimeMillis()))) {
                check(world.addFreshEntity(item),"delayed drop spawn");
            }finally{MutationSourceResolver.restore(previous);}
            var origin=CreateEntityOrigin.read(item);check(origin!=null&&source.equals(origin.source())&&owner.equals(origin.owner()),"item origin after callback");
            var saved=NbtSerde.snapshotEntity(world,item);var restored=(net.minecraft.world.entity.item.ItemEntity)EntityType.loadEntityRecursive(saved,world,e->e);
            check(origin.equals(CreateEntityOrigin.read(restored)),"item origin after save/load");
            var pickup=CartLootTransferAudit.class.getDeclaredMethod("source",net.minecraft.world.entity.item.ItemEntity.class);pickup.setAccessible(true);
            check(kind==CreateContraptionKind.fromSource((String)pickup.invoke(null,restored))&&id.equals(CreateContraptionKind.id((String)pickup.invoke(null,restored))),"delayed drop pickup lost kind/id");
            item.discard();tested+=3;
        }
        // AuthCore is absent: establish the same detached tick source/cause without enabling publication.
        var pos=world.getSharedSpawnPos().offset(25,20,25);
        var source=CreateContraptionKind.BEARING.source(UUID.randomUUID(),"tnt");var owner=UUID.randomUUID();
        java.util.List<net.minecraft.world.entity.item.ItemEntity> drops=new java.util.ArrayList<>();
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener=event->{
            if(event.getLevel()==world&&event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item
                    &&item.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos))<12)drops.add(item);
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
        try {
            world.setBlock(pos,Blocks.CHEST.defaultBlockState(),3);
            ((net.minecraft.world.level.block.entity.ChestBlockEntity)world.getBlockEntity(pos)).setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,7));
            var tnt=new net.minecraft.world.entity.item.PrimedTnt(world,pos.getX()+.5,pos.getY()+1,pos.getZ()+.5,null);tnt.setFuse(1);
            var previous=MutationSourceResolver.enter(source);
            try(var cause=CauseContext.push(new CauseContext.Cause(owner,"ExplosiveOwner",CauseContext.Kind.OTHER,pos,null,System.currentTimeMillis()))){check(world.addFreshEntity(tnt),"native TNT spawn");}
            finally{MutationSourceResolver.restore(previous);}
            previous=MutationSourceResolver.enter(MutationSourceResolver.sourceFor(tnt));
            try(var cause=CreateEntityOrigin.cause(tnt)){world.tickNonPassenger(tnt);}finally{MutationSourceResolver.restore(previous);}
            check(tnt.isRemoved()&&world.getBlockState(pos).isAir(),"native TNT explosion did not destroy chest");
            check(drops.stream().anyMatch(item->item.getItem().is(net.minecraft.world.item.Items.DIAMOND)),"native TNT inventory drop missing");
            for(var item:drops){var origin=CreateEntityOrigin.read(item);check(origin!=null&&source.equals(origin.source())&&owner.equals(origin.owner()),"native explosion lost item origin");}
            tested++;
            drops.forEach(net.minecraft.world.entity.Entity::discard);drops.clear();
            source=CreateContraptionKind.OTHER.source(UUID.randomUUID(),"falling_block");
            world.setBlock(pos,Blocks.ANVIL.defaultBlockState(),3);
            previous=MutationSourceResolver.enter(source);
            net.minecraft.world.entity.item.FallingBlockEntity falling;
            try(var cause=CauseContext.push(new CauseContext.Cause(null,null,CauseContext.Kind.OTHER,pos,null,System.currentTimeMillis()))){falling=net.minecraft.world.entity.item.FallingBlockEntity.fall(world,pos,Blocks.ANVIL.defaultBlockState());}
            finally{MutationSourceResolver.restore(previous);}
            falling.time=601;
            previous=MutationSourceResolver.enter(MutationSourceResolver.sourceFor(falling));
            try(var cause=CreateEntityOrigin.cause(falling)){world.tickNonPassenger(falling);}finally{MutationSourceResolver.restore(previous);}
            check(falling.isRemoved()&&drops.stream().anyMatch(item->item.getItem().is(net.minecraft.world.item.Items.ANVIL)),"native timed-out falling block drop missing");
            for(var item:drops){var origin=CreateEntityOrigin.read(item);check(origin!=null&&source.equals(origin.source())&&origin.owner()==null,"native falling block invented owner or lost origin");}
            tested++;
        }finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);drops.forEach(net.minecraft.world.entity.Entity::discard);world.setBlock(pos,Blocks.AIR.defaultBlockState(),3);}
        check(CauseContext.peek()==null&&CreateActorContext.current()==null,"delayed item context leaked");
        System.out.println("CREATE_DELAYED_DROPS_NATIVE_OK: "+tested+" scenarios; ordinary item allocation guard, typed persistent drops, native TNT explosion and falling-block timeout");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

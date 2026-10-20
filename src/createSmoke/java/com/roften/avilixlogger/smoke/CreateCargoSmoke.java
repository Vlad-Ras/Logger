package com.roften.avilixlogger.smoke;

import com.mojang.authlib.GameProfile;
import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.compat.create.mixin.CartStorageAccessor;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllEntityTypes;
import com.simibubi.create.api.contraption.storage.item.chest.ChestMountedStorage;
import com.simibubi.create.api.contraption.storage.item.menu.StorageInteractionWrapper;
import com.simibubi.create.content.contraptions.*;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.behaviour.dispenser.*;
import com.simibubi.create.content.contraptions.behaviour.dispenser.storage.DispenserMountedStorage;
import com.simibubi.create.content.logistics.crate.CreativeCrateMountedStorage;
import com.simibubi.create.content.fluids.tank.storage.creative.CreativeFluidTankMountedStorage;
import com.simibubi.create.content.equipment.toolbox.ToolboxMountedStorage;
import com.simibubi.create.content.logistics.depot.storage.DepotMountedStorage;
import com.simibubi.create.impl.contraption.storage.FallbackMountedStorage;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.content.contraptions.piston.PistonContraption;
import com.simibubi.create.content.fluids.tank.storage.FluidTankMountedStorage;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import com.simibubi.create.content.kinetics.deployer.*;
import com.simibubi.create.content.kinetics.drill.DrillBlock;
import com.simibubi.create.content.logistics.vault.ItemVaultMountedStorage;
import com.simibubi.create.content.trains.entity.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import java.util.*;

/** Native mounted handlers and mechanism transfers; detached capture never enables journal authorization. */
public final class CreateCargoSmoke {
    private static final BlockPos CHEST = BlockPos.ZERO, SECOND = new BlockPos(0,0,1), VAULT = new BlockPos(1,0,0), TANK = new BlockPos(1,0,1), ACTOR = new BlockPos(2,0,1);
    private static final UUID OWNER = UUID.randomUUID();
    private static final BlockPos DISPENSER=new BlockPos(3,0,0),CREATIVE=new BlockPos(4,0,0),CREATIVE_TANK=new BlockPos(4,0,1),TOOLBOX=new BlockPos(5,0,0),DEPOT=new BlockPos(6,0,0),FALLBACK=new BlockPos(7,0,0);
    private static int scenarios;
    public static void run(ServerLevel world) throws Exception {
        scenarios=0;
        for(var kind:new CreateContraptionKind[]{CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER}) exercise(world,kind);
        int previous=scenarios;
        for(var kind:new CreateContraptionKind[]{CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER}) special(world,kind);
        System.out.println("CREATE_SPECIAL_STORAGE_NATIVE_OK: "+(scenarios-previous)+" scenarios; real dropper/dispenser refill+dispense+bucket, creative supply+void+automation, toolbox+depot+fallback");
        int detailStart=scenarios;
        for(var kind:new CreateContraptionKind[]{CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER}) details(world,kind);
        System.out.println("CREATE_BLOCK_DETAILS_NATIVE_OK: "+(scenarios-detailStart)+" scenarios; native lever+two-part door, in-place NBT coalescing, replacement+no-op+failure, captured player name");
        int linkedStart=scenarios;
        for(var kind:new CreateContraptionKind[]{CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER}) linked(world,kind);
        System.out.println("CREATE_LINKED_CONTAINER_DEPOT_NATIVE_OK: "+(scenarios-linkedStart)+" scenarios; native Container/hopper/double chest, recipient replay+compensation, locks+unknown+partial failure, real depot hand swap");
        check(CreateActorContext.current()==null&&CreateMechanismContext.current()==null&&MutationSourceResolver.resolveExternalSource()==null&&CauseContext.peek()==null,"cargo context leaked");
        System.out.println("CREATE_CARGO_NATIVE_OK: "+previous+" scenarios; mounted chest+vault+fluid, real deployer take+return, drill collection, native menu+cursor, stale binding, cart+train+bearing+piston");
    }
    private static void exercise(ServerLevel world,CreateContraptionKind kind)throws Exception {
        var entity=entity(world,kind);var storage=entity.getContraption().getStorage();CartStorageAudit.bind(entity);
        var chest=storage.getAllItemStorages().get(CHEST);var second=storage.getAllItemStorages().get(SECOND);var vault=storage.getAllItemStorages().get(VAULT);
        var initialRef=CartStorageAudit.ref(chest);check(initialRef!=null&&initialRef.entity().get()==entity,"native storage not bound");
        check(!CartStorageAudit.locked(initialRef),"non-cart storage queried missing cart state");
        var aggregate=storage.getAllItems();
        check(java.util.concurrent.CompletableFuture.supplyAsync(()->CartStorageAudit.ref(chest)==null&&CartStorageAudit.owner(aggregate)==null).get(2,java.util.concurrent.TimeUnit.SECONDS),"worker accessed live storage attribution");scenarios++;
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            chest.insertItem(3,new ItemStack(Items.DIAMOND,4),true);chest.extractItem(3,2,true);storage.getFluids().fill(new FluidStack(Fluids.WATER,200),FluidAction.SIMULATE);
            check(capture.rows().isEmpty(),"simulation emitted cargo");scenarios++;
            check(chest.insertItem(3,new ItemStack(Items.DIAMOND,4),false).isEmpty(),"native chest insert failed");
            cell(capture.rows().getLast(),world,entity,CHEST,3,"Item",0,4);scenarios++;
            check(chest.extractItem(3,2,false).getCount()==2,"native chest extract failed");
            cell(capture.rows().getLast(),world,entity,CHEST,3,"Item",4,2);scenarios++;
            var tool=new ItemStack(Items.IRON_PICKAXE);tool.set(DataComponents.DAMAGE,7);vault.setStackInSlot(1,tool);
            cell(capture.rows().getLast(),world,entity,VAULT,1,"Item",0,1);
            var wear=capture.rows().getLast();wear.materializeSnapshots();check(wear.beAfter.contains("minecraft:damage"),"tool component lost");scenarios++;
            int before=capture.rows().size();vault.setStackInSlot(1,tool.copy());chest.extractItem(20,1,false);
            check(capture.rows().size()==before,"unchanged item emitted cargo");scenarios++;
            check(storage.getFluids().fill(new FluidStack(Fluids.WATER,750),FluidAction.EXECUTE)==750,"native tank fill failed");
            cell(capture.rows().getLast(),world,entity,TANK,0,"Fluid",0,750);scenarios++;
            check(storage.getFluids().drain(250,FluidAction.EXECUTE).getAmount()==250,"native tank drain failed");
            cell(capture.rows().getLast(),world,entity,TANK,0,"Fluid",750,500);scenarios++;
            check(storage.getFluids().drain(new FluidStack(Fluids.WATER,100),FluidAction.EXECUTE).getAmount()==100,"native typed drain failed");
            cell(capture.rows().getLast(),world,entity,TANK,0,"Fluid",500,400);scenarios++;
        }
        // Same wrappers must reuse bindings without scanning their cells.
        var bound=CartStorageAudit.class.getDeclaredField("BOUND");bound.setAccessible(true);var bindings=(Map<?,?>)bound.get(null);Object cached=bindings.get(entity);
        for(int i=0;i<100;i++)CartStorageAudit.ensureBound(entity);check(bindings.get(entity)==cached,"idle binding was rebuilt");scenarios++;
        var target=new BlockPos(48+kind.ordinal()*8,128,32);world.getChunkAt(target);world.setBlock(target.below(),Blocks.STONE.defaultBlockState(),3);
        chest.setStackInSlot(3,ItemStack.EMPTY);vault.setStackInSlot(1,ItemStack.EMPTY);chest.setStackInSlot(0,new ItemStack(Items.STONE,2));
        var data=new CompoundTag();data.putUUID("Owner",OWNER);data.putString("Mode","USE");
        var deployer=new MovementContext(world,new StructureBlockInfo(ACTOR,AllBlocks.DEPLOYER.getDefaultState().setValue(DeployerBlock.FACING,Direction.EAST),data),entity.getContraption());
        deployer.position=Vec3.atCenterOf(target);var fake=new DeployerFakePlayer(world,OWNER);deployer.temporaryData=fake;
        var actor=new DeployerMovementBehaviour();world.setBlock(target,Blocks.AIR.defaultBlockState(),3);
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            actor.visitNewPosition(deployer,target);check(world.getBlockState(target).is(Blocks.STONE),"deployer did not place cargo item");
            var rows=capture.rows();check(rows.size()==1,"grab duplicated or missed: "+rows.size());var row=rows.getFirst();row.materializeSnapshots();
            check(after(row).getString("Operation").equals("deployer_take")&&row.actorUuid==null && "SYSTEM".equals(row.actorName),"grab reason/owner missing");
            check("create:deployer@2,0,1".equals(CreateMechanismContext.description(row.source)),"grab mechanism missing");cell(row,world,entity,CHEST,0,"Item",2,1);scenarios++;
        }
        world.setBlock(target,Blocks.AIR.defaultBlockState(),3);fake.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.STONE));fake.getInventory().setItem(1,new ItemStack(Items.EMERALD,2));
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            actor.visitNewPosition(deployer,target);check(fake.getInventory().getItem(1).isEmpty(),"deployer did not dispose extra item");
            check(capture.rows().size()==1,"return duplicated or missed");var row=capture.rows().getFirst();row.materializeSnapshots();
            check(after(row).getString("Operation").equals("deployer_return")&&after(row).getBoolean("External")&&row.actorUuid==null && "SYSTEM".equals(row.actorName),"return lost endpoints/owner");scenarios++;
        }
        world.setBlock(target,Blocks.STONE.defaultBlockState(),3);
        var drill=new MovementContext(world,new StructureBlockInfo(ACTOR,AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(DrillBlock.FACING,Direction.EAST),new CompoundTag()),entity.getContraption());drill.position=Vec3.atCenterOf(target);
        var destroy=BlockBreakingMovementBehaviour.class.getDeclaredMethod("destroyBlock",MovementContext.class,BlockPos.class);destroy.setAccessible(true);
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            destroy.invoke(new com.simibubi.create.content.kinetics.drill.DrillMovementBehaviour(),drill,target);
            check(world.getBlockState(target).isAir()&&capture.rows().size()==1,"drill cargo interception failed");var row=capture.rows().getFirst();row.materializeSnapshots();
            check(!after(row).getBoolean("External")&&after(row).getString("Operation").equals("block_break_loot"),"verified drill collection is external");
            check("create:mechanical_drill@2,0,1".equals(CreateMechanismContext.description(row.source)),"drill mechanism missing");scenarios++;
        }
        menu(world,entity,chest,second);scenarios+=2;
        automation(world,entity,target.above(4));
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            try(var operation=CreateCargoAudit.operation(drill,"block_break_loot",true)){throw new IllegalArgumentException("fixture operation failure");}
            catch(IllegalArgumentException expected){}
            check(CreateMechanismContext.current()==null&&MutationSourceResolver.resolveExternalSource()==null&&CreateActorContext.current()==null,"operation failure leaked scope");
        }scenarios++;
        // Native read replaces every leaf and wrapper on the SAME manager. Stale handlers must lose attribution.
        var oldAggregate=storage.getAllItems();var saved=new CompoundTag();storage.write(saved,world.registryAccess(),false);storage.read(saved,world.registryAccess(),false,null);CartStorageAudit.ensureBound(entity);
        check(CartStorageAudit.ref(chest)==null,"old storage leaf kept attribution after native read");
        check(CartStorageAudit.owner(oldAggregate)==null,"old aggregate kept storage alias after native read");
        var fresh=storage.getAllItemStorages().get(CHEST);
        try(var capture=CreateCargoAudit.captureChanges(entity)){fresh.insertItem(25,new ItemStack(Items.GOLD_INGOT),false);check(capture.rows().size()==1,"new native leaf not rebound");cell(capture.rows().getFirst(),world,entity,CHEST,25,"Item",0,1);}scenarios++;
        try(var capture=CreateCargoAudit.captureChanges(entity)){chest.insertItem(25,new ItemStack(Items.GOLD_INGOT),false);check(capture.rows().isEmpty(),"stale leaf polluted live construction");}scenarios++;
        if(kind==CreateContraptionKind.TRAIN){
            var other=entity(world,kind);((CarriageContraptionEntity)other).trainId=((CarriageContraptionEntity)entity).trainId;CartStorageAudit.bind(other);
            var a=CreateCargoAudit.row(entity,ActionType.CART_CONTENT_CHANGE,"cargo",new CompoundTag(),new CompoundTag(),"first");
            var b=CreateCargoAudit.row(other,ActionType.CART_CONTENT_CHANGE,"cargo",new CompoundTag(),new CompoundTag(),"second");a.materializeSnapshots();b.materializeSnapshots();
            check(CreateContraptionKind.id(a.source).equals(CreateContraptionKind.id(b.source))&&!after(a).getUUID("StorageEntity").equals(after(b).getUUID("StorageEntity")),"two carriages conflated");scenarios++;
        }
    }
    private static void details(ServerLevel world,CreateContraptionKind kind)throws Exception {
        var entity=entity(world,kind);var c=entity.getContraption();var pos=new BlockPos(8,0,0);
        if(entity instanceof CarriageContraptionEntity train){
            var carriage=new Carriage(new CarriageBogey(AllBlocks.SMALL_BOGEY.get(),false,new CompoundTag()),null,0);
            carriage.getDimensional(world).entity=new java.lang.ref.WeakReference<>(train);
            var field=CarriageContraptionEntity.class.getDeclaredField("carriage");field.setAccessible(true);field.set(train,carriage);
        }
        var player=new net.minecraft.server.level.ServerPlayer(world.getServer(),world,new GameProfile(UUID.randomUUID(),"ActorDetails"),net.minecraft.server.level.ClientInformation.createDefault());
        var victim=FakePlayerFactory.get(world,new GameProfile(UUID.randomUUID(),"VictimExact"));
        var targetRow=new LogEntry();targetRow.type=ActionType.ENTITY_ATTACK;targetRow.actorName="ActorDetails";EntityLogIdentity.capture(targetRow,victim);
        check(targetRow.entityName.equals("VictimExact")&&targetRow.entityUuid.equals(victim.getUUID())&&LogText.toChatLine(targetRow).getString().contains("ударил VictimExact"),"live player target name missing");scenarios++;
        for(var type:List.of(ActionType.ENTITY_ATTACK,ActionType.ENTITY_INTERACT,ActionType.ENTITY_MOUNT,ActionType.ENTITY_DISMOUNT,ActionType.ENTITY_CONTAINER_OPEN,ActionType.PROJECTILE_HIT,ActionType.ENTITY_DEATH)){
            targetRow.type=type;check(LogText.toChatLine(world,targetRow).getString().contains("VictimExact"),"target missing from native formatted "+type);
        }
        c.getBlocks().put(pos,new StructureBlockInfo(pos,Blocks.LEVER.defaultBlockState(),new CompoundTag()));
        c.getInteractors().put(pos,new com.simibubi.create.content.contraptions.behaviour.LeverMovingInteraction());
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            check(entity.handlePlayerInteraction(player,pos,Direction.UP,InteractionHand.MAIN_HAND),"native moving lever failed");
            check(capture.rows().size()==1,"moving lever change missing/duplicate: kind="+kind+" active="+CreateCargoAudit.active(entity)+" rows="+capture.rows().size()+" state="+c.getBlocks().get(pos).state());var row=capture.rows().getFirst();row.materializeSnapshots();
            check(player.getUUID().equals(row.actorUuid)&&CreateContraptionKind.fromSource(row.source)==kind&&BlockChangeDetails.changes(row).stream().anyMatch(s->s.contains("powered: false → true")),"moving lever actor/kind/exact change missing");
            check(LogText.toChatLine(world,row).getString().contains("powered: false → true"),"main structure row does not specify change");
        }scenarios++;
        var lower=Blocks.OAK_DOOR.defaultBlockState();var upper=lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
        c.getBlocks().put(pos,new StructureBlockInfo(pos,lower,null));c.getBlocks().put(pos.above(),new StructureBlockInfo(pos.above(),upper,null));
        c.getInteractors().put(pos,new com.simibubi.create.content.contraptions.behaviour.DoorMovingInteraction());
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            entity.handlePlayerInteraction(player,pos,Direction.UP,InteractionHand.MAIN_HAND);check(capture.rows().size()==2,"two-part door not captured separately");
            var addresses=new HashSet<Long>();for(var row:capture.rows()){row.materializeSnapshots();addresses.add(after(row).getLong("LocalPos"));check(BlockChangeDetails.changes(row).stream().anyMatch(s->s.contains("open: false → true")),"door open change absent");}
            check(addresses.equals(Set.of(pos.asLong(),pos.above().asLong())),"door local addresses conflated");
        }scenarios++;
        var settings=new CompoundTag();settings.putInt("Speed",4);var data=new CompoundTag();data.put("Settings",settings);
        c.getBlocks().put(pos,new StructureBlockInfo(pos,Blocks.STONE.defaultBlockState(),data));
        c.getInteractors().put(pos,new com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour(){
            @Override public boolean handlePlayerInteraction(net.minecraft.world.entity.player.Player p,InteractionHand hand,BlockPos local,AbstractContraptionEntity e){
                var info=e.getContraption().getBlocks().get(local);info.nbt().getCompound("Settings").putInt("Speed",9);
                e.setBlock(local,info);e.setBlock(local,info);return true;
            }
        });
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            entity.handlePlayerInteraction(player,pos,Direction.UP,InteractionHand.MAIN_HAND);check(capture.rows().size()==1,"in-place data change missing/duplicated");
            var row=capture.rows().getFirst();row.materializeSnapshots();data.getCompound("Settings").putInt("Speed",22);
            check(BlockChangeDetails.changes(row).stream().anyMatch(s->s.contains("Settings.Speed: 4 → 9")),"live mutation corrupted detached before/after");
        }scenarios++;
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            entity.setBlock(pos,new StructureBlockInfo(pos,Blocks.DIRT.defaultBlockState(),data.copy()));
            check(capture.rows().size()==1,"direct setBlock replacement not captured");var row=capture.rows().getFirst();row.materializeSnapshots();
            check(BlockChangeDetails.changes(row).stream().anyMatch(s->s.contains("minecraft:stone → minecraft:dirt")),"replacement type not shown");
        }scenarios++;
        try(var capture=CreateCargoAudit.captureChanges(entity)){entity.setBlock(pos,c.getBlocks().get(pos));check(capture.rows().isEmpty(),"unchanged setBlock invented changes");}scenarios++;
        c.getInteractors().put(pos,new com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour(){
            @Override public boolean handlePlayerInteraction(net.minecraft.world.entity.player.Player p,InteractionHand hand,BlockPos local,AbstractContraptionEntity e){
                e.getContraption().getBlocks().get(local).nbt().putBoolean("ChangedBeforeFailure",true);throw new IllegalArgumentException("fixture block interaction failure");
            }
        });
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            try{entity.handlePlayerInteraction(player,pos,Direction.UP,InteractionHand.MAIN_HAND);throw new AssertionError("interaction failure swallowed");}catch(IllegalArgumentException expected){}
            check(capture.rows().size()==1,"real change before exception lost");capture.rows().getFirst().materializeSnapshots();
            check(BlockChangeDetails.changes(capture.rows().getFirst()).stream().anyMatch(s->s.contains("ChangedBeforeFailure")),"exception change unspecified");
            check(CauseContext.peek()==null&&MutationSourceResolver.resolveExternalSource()==null,"interaction exception leaked player/source");
        }scenarios++;
    }
    private static void special(ServerLevel world,CreateContraptionKind kind)throws Exception {
        var entity=entity(world,kind,true);CartStorageAudit.bind(entity);var storage=entity.getContraption().getStorage();
        var dispenser=storage.getAllItemStorages().get(DISPENSER);var chest=storage.getAllItemStorages().get(CHEST);
        var origin=new BlockPos(120+kind.ordinal()*10,128,64);world.getChunkAt(origin);
        world.setBlock(origin.east(),Blocks.AIR.defaultBlockState(),3);
        var context=new MovementContext(world,new StructureBlockInfo(DISPENSER,Blocks.DROPPER.defaultBlockState().setValue(DispenserBlock.FACING,Direction.EAST),new CompoundTag()),entity.getContraption());context.position=Vec3.atCenterOf(origin);
        var dropper=new DropperMovementBehaviour();
        try(var capture=CreateCargoAudit.captureChanges(entity)){dropper.visitNewPosition(context,origin);check(capture.rows().isEmpty(),"empty dropper emitted cargo");}scenarios++;
        dispenser.setStackInSlot(0,new ItemStack(Items.STONE,2));
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            dropper.visitNewPosition(context,origin);check(capture.rows().size()==1,"dropper consumption duplicated/missed");
            var row=capture.rows().getFirst();cell(row,world,entity,DISPENSER,0,"Item",2,1);reason(row,"dropper_dispense","minecraft:dropper@3,0,0");
        }scenarios++;
        world.setBlock(origin.east(),Blocks.CHEST.defaultBlockState(),3);
        var external=(net.minecraft.world.Container)world.getBlockEntity(origin.east());for(int i=0;i<external.getContainerSize();i++)external.setItem(i,new ItemStack(Items.STONE,64));
        dispenser.setStackInSlot(0,new ItemStack(Items.STONE,2));
        try(var capture=CreateCargoAudit.captureChanges(entity)){dropper.visitNewPosition(context,origin);check(capture.rows().isEmpty()&&dispenser.getStackInSlot(0).getCount()==2,"blocked dropper invented a change");}scenarios++;
        external.clearContent();
        dispenser.setStackInSlot(0,new ItemStack(Items.STONE));chest.setStackInSlot(0,new ItemStack(Items.STONE,10));
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            dropper.visitNewPosition(context,origin);var rows=capture.rows();check(rows.size()==4,"dropper refill events duplicated/missed: "+rows.size());
            cell(rows.get(0),world,entity,CHEST,0,"Item",10,0);reason(rows.get(0),"dispenser_refill","minecraft:dropper@3,0,0");
            cell(rows.get(1),world,entity,DISPENSER,0,"Item",1,11);reason(rows.get(1),"dispenser_refill","minecraft:dropper@3,0,0");
            cell(rows.get(2),world,entity,DISPENSER,0,"Item",11,10);reason(rows.get(2),"dropper_dispense","minecraft:dropper@3,0,0");
            check(external.getItem(0).getCount()==1,"native dropper did not transfer to Container");
            linkedRows(rows,entity,true);
        }scenarios++;
        for(int i=0;i<dispenser.getSlots();i++)dispenser.setStackInSlot(i,ItemStack.EMPTY);
        dispenser.setStackInSlot(0,new ItemStack(Items.BUCKET,2));world.setBlock(origin.east(),Blocks.WATER.defaultBlockState(),3);
        context.state=Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING,Direction.EAST);
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            new DispenserMovementBehaviour().visitNewPosition(context,origin);var rows=capture.rows();
            check(rows.size()==2&&world.getFluidState(origin.east()).isEmpty(),"native dispenser bucket pickup failed: "+rows.size());
            for(var row:rows){row.materializeSnapshots();reason(row,"dispenser_dispense","minecraft:dispenser@3,0,0");}
            check(dispenser.getStackInSlot(0).getCount()==1&&dispenser.getStackInSlot(1).is(Items.WATER_BUCKET),"filled bucket return lost");
        }scenarios++;
        dispenser.setStackInSlot(0,new ItemStack(Items.STONE,2));dispenser.setStackInSlot(1,ItemStack.EMPTY);
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            var failing=new DropperMovementBehaviour(){@Override protected com.simibubi.create.api.contraption.dispenser.MountedDispenseBehavior getDispenseBehavior(MovementContext c,BlockPos p,ItemStack s){throw new IllegalArgumentException("native dispense failure");}};
            try{failing.visitNewPosition(context,origin);throw new AssertionError("fixture failure swallowed");}catch(IllegalArgumentException expected){}
            check(CreateMechanismContext.current()==null&&CreateActorContext.current()==null&&MutationSourceResolver.resolveExternalSource()==null,"dispense exception leaked context");
        }scenarios++;
        var crate=storage.getAllItemStorages().get(CREATIVE);var infinite=storage.getFluids().storages.get(CREATIVE_TANK);
        if(kind==CreateContraptionKind.MINECART){
            var state=CreateCartAudit.state(entity);state.locked=true;
            try(var capture=CreateCargoAudit.captureChanges(entity)){
                check(crate.extractItem(0,4,false).isEmpty()&&crate.insertItem(1,new ItemStack(Items.STONE,4),false).getCount()==4,"locked creative item store permitted mutation");
                check(infinite.drain(100,FluidAction.EXECUTE).isEmpty()&&infinite.drain(new FluidStack(Fluids.LAVA,100),FluidAction.EXECUTE).isEmpty()&&infinite.fill(new FluidStack(Fluids.WATER,100),FluidAction.EXECUTE)==0,"locked creative fluid store permitted IO");
                check(capture.rows().isEmpty(),"locked creative IO was published");
            }finally{state.locked=false;}scenarios++;
        }
        var input=new ItemStack(Items.DIAMOND,5);input.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("fixture diamond"));
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            crate.extractItem(0,9,true);crate.insertItem(1,input,true);infinite.fill(new FluidStack(Fluids.WATER,150),FluidAction.SIMULATE);infinite.drain(150,FluidAction.SIMULATE);infinite.drain(new FluidStack(Fluids.LAVA,150),FluidAction.SIMULATE);
            check(capture.rows().isEmpty(),"creative simulation emitted IO");scenarios++;
            var supplied=crate.getStackInSlot(0).copy();check(crate.extractItem(0,100,false).getCount()==64,"creative item clamp changed");io(capture.rows().getLast(),entity,CREATIVE,"item","out",64);scenarios++;
            check(crate.insertItem(1,input,false).isEmpty(),"creative input not consumed");io(capture.rows().getLast(),entity,CREATIVE,"item","in",5);
            check(after(capture.rows().getLast()).getCompound("Payload").getCompound("components").contains("minecraft:custom_name"),"creative consumed components lost");scenarios++;
            int previous=capture.rows().size();crate.extractItem(1,2,false);crate.extractItem(0,0,false);crate.insertItem(1,ItemStack.EMPTY,false);crate.setStackInSlot(0,input);
            check(capture.rows().size()==previous&&ItemStack.matches(supplied,crate.getStackInSlot(0)),"creative item no-op changed template/emitted IO");scenarios++;
            check(infinite.drain(150,FluidAction.EXECUTE).getAmount()==150,"creative amount drain failed");io(capture.rows().getLast(),entity,CREATIVE_TANK,"fluid","out",150);scenarios++;
            check(infinite.drain(new FluidStack(Fluids.LAVA,200),FluidAction.EXECUTE).getAmount()==200,"creative typed drain failed");io(capture.rows().getLast(),entity,CREATIVE_TANK,"fluid","out",200);scenarios++;
            check(infinite.fill(new FluidStack(Fluids.WATER,300),FluidAction.EXECUTE)==300,"creative fluid void failed");io(capture.rows().getLast(),entity,CREATIVE_TANK,"fluid","in",300);scenarios++;
            previous=capture.rows().size();infinite.drain(0,FluidAction.EXECUTE);infinite.drain(new FluidStack(Fluids.WATER,200),FluidAction.EXECUTE);infinite.fill(FluidStack.EMPTY,FluidAction.EXECUTE);
            check(capture.rows().size()==previous&&infinite.getFluidInTank(0).getAmount()==8000&&infinite.getFluidInTank(0).getFluid()==Fluids.LAVA,"creative no-op changed fluid template/emitted IO");scenarios++;
        }
        for(var pos:List.of(TOOLBOX,DEPOT,FALLBACK)){
            var handler=storage.getAllItemStorages().get(pos);
            try(var capture=CreateCargoAudit.captureChanges(entity)){
                handler.insertItem(0,new ItemStack(Items.IRON_INGOT,4),true);check(capture.rows().isEmpty(),"special storage simulation emitted");
                check(handler.insertItem(0,new ItemStack(Items.IRON_INGOT,4),false).isEmpty(),"special native storage insert failed");check(capture.rows().size()==1,"special insert duplicate/missing");cell(capture.rows().getLast(),world,entity,pos,0,"Item",0,4);
                check(handler.extractItem(0,2,false).getCount()==2,"special native extract failed");check(capture.rows().size()==2,"special extract duplicate/missing");cell(capture.rows().getLast(),world,entity,pos,0,"Item",4,2);
            }scenarios++;
        }
        infiniteAutomation(world,entity,crate,infinite,origin.above(6));
        var saved=new CompoundTag();storage.write(saved,world.registryAccess(),false);storage.read(saved,world.registryAccess(),false,null);CartStorageAudit.ensureBound(entity);
        check(CartStorageAudit.ref(crate)==null&&CartStorageAudit.ref(infinite)==null,"old creative handlers stayed attributed");
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            storage.getAllItemStorages().get(CREATIVE).extractItem(0,3,false);io(capture.rows().getLast(),entity,CREATIVE,"item","out",3);
            storage.getFluids().storages.get(CREATIVE_TANK).drain(125,FluidAction.EXECUTE);io(capture.rows().getLast(),entity,CREATIVE_TANK,"fluid","out",125);
            check(capture.rows().size()==2,"restored creative storage duplicate/missing");
        }scenarios++;
    }
    private static void linked(ServerLevel world,CreateContraptionKind kind)throws Exception{
        var entity=entity(world,kind,true);CartStorageAudit.bind(entity);var c=entity.getContraption();
        var dispenser=c.getStorage().getAllItemStorages().get(DISPENSER);var depot=(DepotMountedStorage)c.getStorage().getAllItemStorages().get(DEPOT);
        var origin=new BlockPos(180+kind.ordinal()*10,128,90);var target=origin.east();world.getChunkAt(origin);
        var context=new MovementContext(world,new StructureBlockInfo(DISPENSER,Blocks.DROPPER.defaultBlockState().setValue(DispenserBlock.FACING,Direction.EAST),new CompoundTag()),c);context.position=Vec3.atCenterOf(origin);
        context.blockEntityData.putUUID("Owner",OWNER);
        var dropper=new DropperMovementBehaviour();
        var named=new ItemStack(Items.DIAMOND,2);named.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("linked diamond"));
        for(String type:List.of("chest","hopper","double","trapped")){
            world.setBlock(target,Blocks.AIR.defaultBlockState(),3);world.setBlock(target.west(),Blocks.AIR.defaultBlockState(),3);world.setBlock(target.above(),Blocks.AIR.defaultBlockState(),3);
            var block=type.equals("hopper")?Blocks.HOPPER:type.equals("trapped")?Blocks.TRAPPED_CHEST:Blocks.CHEST;
            int slot=type.equals("double")||type.equals("trapped")?27:0;
            if(slot==27){
                world.setBlock(target.west(),block.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.LEFT),2);
                world.setBlock(target,block.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.RIGHT),2);
            }else world.setBlock(target,block.defaultBlockState(),3);
            var container=net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(world,target);check(container!=null,"native recipient missing");container.clearContent();
            for(int i=0;i<slot;i++)container.setItem(i,new ItemStack(Items.STONE,64));
            if(type.equals("hopper"))container.setItem(0,named.copyWithCount(63));
            dispenser.setStackInSlot(0,named.copy());
            try(var capture=CreateCargoAudit.captureChanges(entity)){
                dropper.visitNewPosition(context,origin);var rows=capture.rows();check(rows.size()==2,"direct recipient rows missing: "+type+" "+rows.size());
                linkedRows(rows,entity,true);var a=after(rows.getLast());var b=NbtSerde.fromSnbt(rows.getLast().beBefore);
                check(rows.stream().allMatch(row->row.actorUuid==null && "SYSTEM".equals(row.actorName)&&row.source.contains("minecraft:dropper@3,0,0")),"mounted direct transfer owner/mechanism lost");
                check(a.getBoolean("Mounted")&&a.getList("Slots",10).size()==1,"direct manifest not sparse/mounted");
                var address=a.getList("Slots",10).getCompound(0);check(address.getInt("Slot")==slot&&address.getLong("Pos")==target.asLong(),"direct recipient address lost");
                check(address.getCompound("Item").getCompound("components").contains("minecraft:custom_name"),"direct recipient components lost");
                var actual=CartAutomationStorage.describeContainer(world,target,"west",slot,CartAutomationStorage.containerAt(world,target));check(actual!=null&&CartAutomationStorage.sameAddress(address,actual),"direct address cannot resolve through existing restorer: "+type+" "+address+" vs "+actual);
                replayRecipient(world,address,b.getList("Slots",10).getCompound(0).getCompound("Item"),address.getCompound("Item"));
                check(CartLogDetails.describe(world,rows.getLast()).stream().anyMatch(s->s.getString().contains("Механизм на конструкции")),"mounted manifest details missing");
            }scenarios++;
        }
        world.setBlock(target.west(),Blocks.AIR.defaultBlockState(),3);world.setBlock(target,Blocks.CHEST.defaultBlockState(),3);
        var recipient=(net.minecraft.world.Container)world.getBlockEntity(target);recipient.clearContent();dispenser.setStackInSlot(0,named.copy());
        CartAuditContext.rollbackActive=true;CartRestoreLocks.container(recipient);CartRestoreLocks.block(world,target);
        try(var capture=CreateCargoAudit.captureChanges(entity)){dropper.visitNewPosition(context,origin);check(capture.rows().isEmpty()&&recipient.isEmpty()&&dispenser.getStackInSlot(0).getCount()==2,"direct recipient reservation ignored");}
        finally{CartAuditContext.rollbackActive=false;CartRestoreLocks.clear();}scenarios++;
        world.setBlock(target,Blocks.AIR.defaultBlockState(),3);
        // This detached fixture has no players loading its distant chunk. Make entity lookup visible explicitly.
        var managerField=ServerLevel.class.getDeclaredField("entityManager");managerField.setAccessible(true);
        @SuppressWarnings("unchecked") var manager=(net.minecraft.world.level.entity.PersistentEntitySectionManager<net.minecraft.world.entity.Entity>)managerField.get(world);
        manager.updateChunkStatus(new net.minecraft.world.level.ChunkPos(target),net.minecraft.world.level.entity.Visibility.TRACKED);
        var vehicle=new net.minecraft.world.entity.vehicle.MinecartChest(world,target.getX()+.5,target.getY()+.5,target.getZ()+.5);check(world.addFreshEntity(vehicle),"recipient minecart not spawned");
        try(var capture=CreateCargoAudit.captureChanges(entity)){dropper.visitNewPosition(context,origin);var rows=capture.rows();check(rows.size()==2,"unresolved recipient transfer missing: kind="+kind+" rows="+rows.size()+" receiver="+vehicle.getItem(0));linkedRows(rows,entity,false);var recipientTag=after(rows.getLast()).getList("Slots",10).getCompound(0);check(recipientTag.getBoolean("Unresolved")&&recipientTag.getUUID("ContainerEntity").equals(vehicle.getUUID()),"entity container given false block address or identity lost");}
        finally{vehicle.discard();manager.updateChunkStatus(new net.minecraft.world.level.ChunkPos(target),net.minecraft.world.level.entity.Visibility.HIDDEN);}scenarios++;
        world.setBlock(target,Blocks.CHEST.defaultBlockState(),3);
        var failing=new net.minecraft.world.level.block.entity.ChestBlockEntity(target,world.getBlockState(target)){
            @Override public void setItem(int slot,ItemStack item){super.setItem(slot,item);throw new IllegalArgumentException("fixture recipient failure after write");}
        };world.setBlockEntity(failing);dispenser.setStackInSlot(0,named.copy());
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            try{dropper.visitNewPosition(context,origin);throw new AssertionError("recipient exception swallowed");}catch(IllegalArgumentException expected){}
            var rows=capture.rows();check(rows.size()==1,"partial external write not recorded");rows.getFirst().materializeSnapshots();check(!after(rows.getFirst()).getBoolean("Complete")&&after(rows.getFirst()).getList("Slots",10).getCompound(0).getCompound("Item").getInt("count")==1,"partial recipient write missing/marked complete");
            check(CreateMechanismContext.current()==null&&MutationSourceResolver.resolveExternalSource()==null,"recipient exception leaked scope");
        }scenarios++;
        world.setBlock(target,Blocks.AIR.defaultBlockState(),3);
        c.getBlocks().put(DEPOT,new StructureBlockInfo(DEPOT,AllBlocks.DEPOT.getDefaultState(),new CompoundTag()));
        c.getInteractors().put(DEPOT,new com.simibubi.create.content.logistics.depot.MountedDepotInteractionBehaviour());
        var player=new net.minecraft.server.level.ServerPlayer(world.getServer(),world,new GameProfile(UUID.randomUUID(),"DepotActor"),net.minecraft.server.level.ClientInformation.createDefault());player.getInventory().clearContent();player.getInventory().selected=0;
        for(int mode=0;mode<3;mode++){
            depot.setItem(mode==0?ItemStack.EMPTY:new ItemStack(Items.IRON_INGOT,3));player.setItemInHand(InteractionHand.MAIN_HAND,mode==1?ItemStack.EMPTY:named.copyWithCount(5));
            try(var capture=CreateCargoAudit.captureChanges(entity)){
                check(entity.handlePlayerInteraction(player,DEPOT,Direction.UP,InteractionHand.MAIN_HAND),"native depot swap failed");var rows=capture.rows();check(rows.size()==2,"depot transfer missing/duplicate: "+rows.size());
                var plan=new CartMenuTransferPlan();for(var row:rows){row.materializeSnapshots();check(row.actorUuid.equals(player.getUUID())&&row.source.contains(":depot_swap"),"depot actor/reason lost");var b=NbtSerde.fromSnbt(row.beBefore);var a=after(row);if(a.getString("Kind").equals("menu_items"))plan.manifest(b,a);else plan.cargo(b,a);}
                check(plan.finish().size()==1,"depot hand endpoint not jointly validated");
            }scenarios++;
        }
        try(var capture=CreateCargoAudit.captureChanges(entity)){check(!entity.handlePlayerInteraction(player,DEPOT,Direction.UP,InteractionHand.OFF_HAND)&&capture.rows().isEmpty(),"off-hand depot interaction changed cargo");}scenarios++;
        depot.setItem(ItemStack.EMPTY);player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        try(var capture=CreateCargoAudit.captureChanges(entity)){entity.handlePlayerInteraction(player,DEPOT,Direction.UP,InteractionHand.MAIN_HAND);check(capture.rows().isEmpty(),"empty depot swap emitted rows");}scenarios++;
        player.setItemInHand(InteractionHand.MAIN_HAND,named.copy());CartAuditContext.rollbackActive=true;CartRestoreLocks.player(player);
        try(var capture=CreateCargoAudit.captureChanges(entity)){check(!entity.handlePlayerInteraction(player,DEPOT,Direction.UP,InteractionHand.MAIN_HAND)&&depot.getItem().isEmpty()&&capture.rows().isEmpty(),"reserved player changed depot");}
        finally{CartAuditContext.rollbackActive=false;CartRestoreLocks.clear();}scenarios++;
        c.getInteractors().put(DEPOT,new com.simibubi.create.content.logistics.depot.MountedDepotInteractionBehaviour(){
            @Override public boolean handlePlayerInteraction(net.minecraft.world.entity.player.Player p,InteractionHand hand,BlockPos pos,AbstractContraptionEntity e){super.handlePlayerInteraction(p,hand,pos,e);throw new IllegalArgumentException("fixture depot failure after swap");}
        });
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            try{entity.handlePlayerInteraction(player,DEPOT,Direction.UP,InteractionHand.MAIN_HAND);throw new AssertionError("depot failure swallowed");}catch(IllegalArgumentException expected){}
            var rows=capture.rows();check(rows.size()==2,"failed depot real changes lost");rows.getLast().materializeSnapshots();check(!after(rows.getLast()).getBoolean("Complete"),"failed depot swap marked complete");
            check(CauseContext.peek()==null&&MutationSourceResolver.resolveExternalSource()==null,"depot failure leaked actor");
        }scenarios++;
    }
    private static void linkedRows(List<LogEntry> rows,AbstractContraptionEntity entity,boolean complete){
        var plan=new CartAutomationTransferPlan();UUID transfer=null;
        for(var row:rows){row.materializeSnapshots();var b=NbtSerde.fromSnbt(row.beBefore);var a=after(row);
            check(a.hasUUID("Transfer")&&b.getUUID("Transfer").equals(a.getUUID("Transfer")),"direct transfer linkage missing");
            if(transfer==null)transfer=a.getUUID("Transfer");check(transfer.equals(a.getUUID("Transfer")),"direct endpoints different transfer IDs");
            if(a.getString("Kind").equals("automation")){check(a.getBoolean("Complete")==complete,"direct completeness wrong");plan.manifest(b,a);}else plan.cargo(b,a);
        }
        if(CreateContraptionSources.kind(entity)==CreateContraptionKind.MINECART){
            if(complete)check(plan.finish(CreateCartAudit.state(entity).id).size()==1,"direct native conservation rejected");
            else {try{plan.finish(CreateCartAudit.state(entity).id);throw new AssertionError("incomplete recipient rollback accepted");}catch(IllegalStateException expected){}}
        }
    }
    private static void replayRecipient(ServerLevel world,CompoundTag address,CompoundTag before,CompoundTag after)throws Exception{
        var undo=new CartAutomationTransferPlan.SlotUndo(address,before,after);
        var dataClass=Class.forName("com.roften.avilixlogger.compat.create.CartRollbackCoordinator$AutomationData");var constructor=dataClass.getDeclaredConstructors()[0];constructor.setAccessible(true);
        var data=constructor.newInstance(undo,NbtSerde.readItemStack(before,world.registryAccess()),NbtSerde.readItemStack(after,world.registryAccess()));
        var slotClass=Class.forName("com.roften.avilixlogger.compat.create.CartRollbackCoordinator$AutomationSlot");var slotConstructor=slotClass.getDeclaredConstructors()[0];slotConstructor.setAccessible(true);
        var set=slotClass.getDeclaredMethod("set",Object.class);set.setAccessible(true);var compensate=slotClass.getDeclaredMethod("compensate");compensate.setAccessible(true);var applied=slotClass.getDeclaredField("applied");applied.setAccessible(true);
        var old=CartAuditContext.restoring(true);CartAuditContext.rollbackActive=true;
        try{var slot=slotConstructor.newInstance(world.getServer(),data);set.invoke(slot,NbtSerde.readItemStack(before,world.registryAccess()));applied.setBoolean(slot,true);compensate.invoke(slot);}
        finally{CartAuditContext.restoring(old);CartAuditContext.rollbackActive=false;CartRestoreLocks.clear();}
    }
    private static void infiniteAutomation(ServerLevel world,AbstractContraptionEntity entity,com.simibubi.create.api.contraption.storage.item.MountedItemStorage crate,com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorage infinite,BlockPos origin){
        world.setBlock(origin.below(),Blocks.STONE.defaultBlockState(),3);world.setBlock(origin,AllBlocks.ANDESITE_FUNNEL.getDefaultState(),3);world.setBlock(origin.east(2),Blocks.CHEST.defaultBlockState(),3);world.setBlock(origin.east(4),AllBlocks.FLUID_TANK.getDefaultState(),3);
        var itemCache=net.neoforged.neoforge.capabilities.BlockCapabilityCache.create(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,world,origin.east(2),null);
        var fluidCache=net.neoforged.neoforge.capabilities.BlockCapabilityCache.create(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,world,origin.east(4),null);itemCache.getCapability();fluidCache.getCapability();
        for(boolean fluid:new boolean[]{false,true})for(boolean inbound:new boolean[]{false,true}){
            var scope=CartAutomationAudit.captureChanges(world.getBlockEntity(origin));List<LogEntry> rows;
            try{
                if(fluid){var external=fluidCache.getCapability();if(inbound)infinite.fill(external.drain(100,FluidAction.EXECUTE),FluidAction.EXECUTE);else external.fill(infinite.drain(100,FluidAction.EXECUTE),FluidAction.EXECUTE);}
                else{var external=itemCache.getCapability();if(inbound)crate.insertItem(1,external.extractItem(0,2,false),false);else external.insertItem(0,crate.extractItem(0,2,false),false);}
                rows=scope.snapshotRows(true);
            }finally{scope.finish(true);}
            check(rows.size()==2&&rows.getFirst().type==ActionType.CART_TRANSFER,"creative automation missed IO/endpoint");
            for(var row:rows){row.materializeSnapshots();check(row.actorUuid==null&&"SYSTEM".equals(row.actorName),"creative automation blamed owner");}
            var io=after(rows.getFirst());var manifest=after(rows.getLast());check(manifest.getBoolean("Complete")&&manifest.getList("Slots",10).size()==1,"creative automation lost endpoint completeness");
            var linked=CreateTransferRollbackPlan.prepare(NbtSerde.fromSnbt(rows.getLast().beBefore),manifest);check(linked.creative().size()==1&&linked.external().size()==1,"creative automation omitted guarded source or finite peer");
            check(io.getUUID("Transfer").equals(manifest.getUUID("Transfer")),"creative automation linkage lost");
            io(rows.getFirst(),entity,fluid?CREATIVE_TANK:CREATIVE,fluid?"fluid":"item",inbound?"in":"out",fluid?100:2);scenarios++;
        }
    }
    private static void reason(LogEntry row,String operation,String mechanism){
        row.materializeSnapshots();check(after(row).getString("Operation").equals(operation)&&mechanism.equals(CreateMechanismContext.description(row.source)),"dispenser reason/mechanism lost: "+row.source+" / "+after(row));
    }
    private static void io(LogEntry row,AbstractContraptionEntity entity,BlockPos pos,String resource,String direction,int quantity){
        row.materializeSnapshots();var b=NbtSerde.fromSnbt(row.beBefore);var a=after(row);
        check(row.type==ActionType.CART_TRANSFER&&a.getString("Kind").equals("storage_io")&&a.getString("Direction").equals(direction)&&a.getString("ResourceKind").equals(resource),"creative IO mislabelled");
        check(a.getLong("LocalPos")==pos.asLong()&&a.getUUID("StorageEntity").equals(entity.getUUID())&&CreateContraptionKind.id(row.source).equals(CreateContraptionSources.id(entity))&&CreateContraptionKind.fromSource(row.source)==CreateContraptionSources.kind(entity),"creative IO address/type lost");
        check(a.getCompound("Payload").getInt(resource.equals("item")?"count":"amount")==quantity,"creative IO quantity lost");
        check(b.getCompound("Supplied").equals(a.getCompound("Supplied"))&&!a.getBoolean("Complete"),"creative IO invented storage delta/restorable endpoints");
        check(CartLogDetails.describe(null,row).stream().anyMatch(c->c.getString().contains(direction.equals("in")?"Поглощено":"Выдано")),"creative IO details missing");
    }
    private static void automation(ServerLevel world,AbstractContraptionEntity entity,BlockPos origin)throws Exception {
        var machinePos=origin.east(4);var externalPos=origin.east(2);var fluidPos=origin.east(6);
        world.setBlock(machinePos.below(),Blocks.STONE.defaultBlockState(),3);world.setBlock(machinePos,AllBlocks.ANDESITE_FUNNEL.getDefaultState(),3);
        world.setBlock(externalPos,Blocks.CHEST.defaultBlockState(),3);world.setBlock(fluidPos,AllBlocks.FLUID_TANK.getDefaultState(),3);
        var machine=world.getBlockEntity(machinePos);
        for(boolean fluid:new boolean[]{false,true}){
            world.setBlock(origin,fluid?AllBlocks.PORTABLE_FLUID_INTERFACE.getDefaultState():AllBlocks.PORTABLE_STORAGE_INTERFACE.getDefaultState(),3);
            var psi=(com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity)world.getBlockEntity(origin);
            psi.startTransferringTo(entity.getContraption(),1);
            var timer=psi.getClass().getSuperclass().getDeclaredField("transferTimer");timer.setAccessible(true);timer.setInt(psi,6);
            Object port=fluid?world.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,origin,null):world.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,origin,null);
            check(CartStorageAudit.owner(port)==entity,"typed PSI alias missing");
            var itemCache=fluid?null:net.neoforged.neoforge.capabilities.BlockCapabilityCache.create(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,world,externalPos,null);
            var fluidCache=!fluid?null:net.neoforged.neoforge.capabilities.BlockCapabilityCache.create(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,world,fluidPos,null);
            if(itemCache!=null)itemCache.getCapability();if(fluidCache!=null)fluidCache.getCapability();
            int global=0;
            if(!fluid){entity.getContraption().getStorage().getAllItemStorages().get(CHEST).setStackInSlot(22,new ItemStack(Items.DIAMOND,4));
                var items=(net.neoforged.neoforge.items.IItemHandler)port;
                while(global<items.getSlots()&&!(items.getStackInSlot(global).is(Items.DIAMOND)&&items.getStackInSlot(global).getCount()==4))global++;
                check(global<items.getSlots(),"aggregate slot for chest unresolved");}
            for(boolean inbound:new boolean[]{false,true}){
                var scope=CartAutomationAudit.captureChanges(machine);List<LogEntry> rows;
                try{
                    if(fluid){var external=fluidCache.getCapability();var tank=(net.neoforged.neoforge.fluids.capability.IFluidHandler)port;
                        if(inbound)check(tank.fill(external.drain(100,FluidAction.EXECUTE),FluidAction.EXECUTE)==100,"PSI fluid inbound failed");
                        else check(external.fill(tank.drain(100,FluidAction.EXECUTE),FluidAction.EXECUTE)==100,"PSI fluid outbound failed");
                    }else{var external=itemCache.getCapability();var items=(net.neoforged.neoforge.items.IItemHandler)port;
                        if(inbound)check(items.insertItem(global,external.extractItem(0,2,false),false).isEmpty(),"PSI item inbound failed");
                        else check(external.insertItem(0,items.extractItem(global,2,false),false).isEmpty(),"PSI item outbound failed");}
                    rows=scope.snapshotRows(true);
                }finally{scope.finish(true);}
                check(rows.size()==2,"typed automation cargo/manifest duplicated or missing: "+rows.size());
                for(var row:rows){row.materializeSnapshots();check(row.actorUuid==null&&"SYSTEM".equals(row.actorName),"machine blamed owner");}
                var change=after(rows.getFirst());var manifest=after(rows.getLast());
                check(manifest.getBoolean("Complete")&&manifest.getList("Slots",10).size()==1&&manifest.getUUID("StorageEntity").equals(entity.getUUID()),"typed automation endpoint incomplete");
                check(change.getUUID("Transfer").equals(manifest.getUUID("Transfer"))&&change.getString("TransferKind").equals("automation"),"typed automatic transfer not linked");
                cell(rows.getFirst(),world,entity,fluid?TANK:CHEST,fluid?0:22,fluid?"Fluid":"Item",fluid?(inbound?300:400):(inbound?2:4),fluid?(inbound?400:300):(inbound?4:2));
                scenarios++;
            }
        }
    }
    private static void menu(ServerLevel world,AbstractContraptionEntity entity,com.simibubi.create.api.contraption.storage.item.MountedItemStorage chest,com.simibubi.create.api.contraption.storage.item.MountedItemStorage second)throws Exception {
        second.setStackInSlot(4,new ItemStack(Items.DIAMOND,12));var player=FakePlayerFactory.get(world,new GameProfile(UUID.randomUUID(),"cargo_menu"));
        var container=new StorageInteractionWrapper(new CombinedInvWrapper(chest,second),p->true,p->{});
        var menu=new ChestMenu(MenuType.GENERIC_9x6,1,player.getInventory(),container,6);player.containerMenu=menu;
        var constructor=CartMenuTransferAudit.Scope.class.getDeclaredConstructor(net.minecraft.server.level.ServerPlayer.class,CartStorageAudit.Ref.class,boolean.class);constructor.setAccessible(true);
        var scope=constructor.newInstance(player,CartStorageAudit.ref(chest),false);menu.clicked(31,0,ClickType.PICKUP,player);
        var rows=scope.rows(true,false);check(rows.size()==2&&menu.getCarried().getCount()==12,"native double-chest menu transfer missing");
        for(var row:rows){row.materializeSnapshots();check(row.actorUuid==null&&"SYSTEM".equals(row.actorName),"technical menu player attributed to a human");}
        cell(rows.getFirst(),world,entity,SECOND,4,"Item",12,0);
        check(after(rows.getLast()).getBoolean("Complete")&&after(rows.getFirst()).getUUID("Transfer").equals(after(rows.getLast()).getUUID("Transfer")),"menu linkage incomplete");
        check(CartLogDetails.describe(world,rows.getLast()).stream().anyMatch(c->c.getString().contains("Курсор игрока")),"menu cursor omitted in details");
        var closing=constructor.newInstance(player,CartStorageAudit.ref(chest),true);player.closeContainer();var closed=closing.rows(true,true);
        check(closed.size()==1&&after(materialize(closed.getFirst())).getBoolean("Complete")&&player.getInventory().getItem(0).getCount()==12,"cursor return on native close lost");
    }
    private static AbstractContraptionEntity entity(ServerLevel world,CreateContraptionKind kind)throws Exception {
        return entity(world,kind,false);
    }
    private static AbstractContraptionEntity entity(ServerLevel world,CreateContraptionKind kind,boolean special)throws Exception {
        Contraption c=switch(kind){case MINECART->new MountedContraption();case TRAIN->new CarriageContraption(Direction.NORTH);case BEARING->new BearingContraption(false,Direction.UP);default->new PistonContraption(Direction.EAST,false);};
        c.anchor=BlockPos.ZERO;c.bounds=new AABB(BlockPos.ZERO);
        if(c instanceof CarriageContraption){var original=Contraption.class.getDeclaredField("storage");original.setAccessible(true);var proxy=CarriageContraption.class.getDeclaredField("storageProxy");proxy.setAccessible(true);proxy.set(c,original.get(c));}
        var access=(CartStorageAccessor)c.getStorage();access.avilixlogger$addItem(new ChestMountedStorage(new ItemStackHandler(27)),CHEST);access.avilixlogger$addItem(new ChestMountedStorage(new ItemStackHandler(27)),SECOND);
        var vaultData=new CompoundTag();vaultData.putInt("Size",3);access.avilixlogger$addItem(ItemVaultMountedStorage.fromLegacy(world.registryAccess(),vaultData),VAULT);
        var tank=new CompoundTag();tank.putInt("Capacity",8000);access.avilixlogger$addFluid(FluidTankMountedStorage.fromLegacy(world.registryAccess(),tank),TANK);
        if(special){
            access.avilixlogger$addItem(new DispenserMountedStorage(new ItemStackHandler(9)),DISPENSER);
            access.avilixlogger$addItem(new CreativeCrateMountedStorage(new ItemStack(Items.EMERALD)),CREATIVE);
            var creativeTank=new CompoundTag();creativeTank.putInt("Capacity",8000);creativeTank.put("ProvidedStack",new FluidStack(Fluids.LAVA,8000).save(world.registryAccess()));
            access.avilixlogger$addFluid(CreativeFluidTankMountedStorage.fromLegacy(world.registryAccess(),creativeTank),CREATIVE_TANK);
            var toolbox=new CompoundTag();toolbox.putInt("Size",32);access.avilixlogger$addItem(ToolboxMountedStorage.fromLegacy(world.registryAccess(),toolbox),TOOLBOX);
            access.avilixlogger$addItem(DepotMountedStorage.fromLegacy(world.registryAccess(),new CompoundTag()),DEPOT);
            access.avilixlogger$addItem(new FallbackMountedStorage(new ItemStackHandler(3)),FALLBACK);
        }
        AbstractContraptionEntity entity;
        if(kind==CreateContraptionKind.MINECART)entity=OrientedContraptionEntity.create(world,c,Direction.NORTH);
        else{entity=kind==CreateContraptionKind.TRAIN?new CarriageContraptionEntity(AllEntityTypes.CARRIAGE_CONTRAPTION.get(),world):new ControlledContraptionEntity(AllEntityTypes.CONTROLLED_CONTRAPTION.get(),world);
            var set=AbstractContraptionEntity.class.getDeclaredMethod("setContraption",Contraption.class);set.setAccessible(true);set.invoke(entity,c);if(entity instanceof CarriageContraptionEntity train)train.trainId=UUID.randomUUID();}
        return entity;
    }
    private static void cell(LogEntry row,ServerLevel world,AbstractContraptionEntity entity,BlockPos pos,int slot,String field,int before,int after){
        row.materializeSnapshots();var b=NbtSerde.fromSnbt(row.beBefore);var a=after(row);
        check(CreateContraptionKind.fromSource(row.source)==CreateContraptionSources.kind(entity)&&CreateContraptionKind.id(row.source).equals(CreateContraptionSources.id(entity)),"cargo type/ID lost");
        check(a.getUUID("StorageEntity").equals(entity.getUUID())&&a.getLong("LocalPos")==pos.asLong()&&a.getInt(field.equals("Item")?"Slot":"Tank")==slot,"cargo cell address lost");
        String count=field.equals("Item")?"count":"amount";
        check(b.getCompound(field).getInt(count)==before&&a.getCompound(field).getInt(count)==after,"cargo counts lost: "+b+" -> "+a);
        check(CartLogDetails.describe(world,row).stream().anyMatch(c->c.getString().contains("До:")),"cargo details lost");
    }
    private static CompoundTag after(LogEntry row){return NbtSerde.fromSnbt(row.beAfter);}
    private static LogEntry materialize(LogEntry row){row.materializeSnapshots();return row;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}

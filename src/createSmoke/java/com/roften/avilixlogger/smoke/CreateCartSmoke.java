package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.content.contraptions.mounted.MinecartContraptionItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Development-only source set; never included in the shipped mod. No AuthCore bypass. */
@EventBusSubscriber(modid="avilixlogger")
public final class CreateCartSmoke {
    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if(!Boolean.getBoolean("avilixlogger.createSmoke"))return;
        try {
            var world=event.getServer().overworld();
            MountedContraption c=new MountedContraption();c.anchor=BlockPos.ZERO;c.bounds=new net.minecraft.world.phys.AABB(BlockPos.ZERO);
            c.getBlocks().put(BlockPos.ZERO,new StructureBlockInfo(BlockPos.ZERO,Blocks.STONE.defaultBlockState(),null));
            var inventory=new net.neoforged.neoforge.items.ItemStackHandler(9);
            inventory.setStackInSlot(3,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,12));
            var storage=(com.roften.avilixlogger.compat.create.mixin.CartStorageAccessor)c.getStorage();
            storage.avilixlogger$addItem(new com.simibubi.create.impl.contraption.storage.FallbackMountedStorage(inventory),BlockPos.ZERO);
            var fluid=(net.minecraft.nbt.CompoundTag)new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,1250).save(world.registryAccess());
            fluid.putInt("Capacity",4000);
            storage.avilixlogger$addFluid(com.simibubi.create.content.fluids.tank.storage.FluidTankMountedStorage.fromLegacy(world.registryAccess(),fluid),BlockPos.ZERO);
            var entity=OrientedContraptionEntity.create(world,c,Direction.NORTH);
            var cart=new Minecart(world,0,128,0);entity.setPos(0,128,0);entity.startRiding(cart,true);
            CartAuditState state=((CartAuditAccess)c).avilixlogger$cartState();state.owner=java.util.UUID.randomUUID();state.ownerName="smoke";
            var item=MinecartContraptionItem.create(AbstractMinecart.Type.RIDEABLE,entity);
            check(state.id.equals(CreateCartAudit.itemId(item)),"packed ID");
            var roundTrip=Contraption.fromNBT(world,c.writeNBT(world.registryAccess(),false),false);
            check(state.id.equals(((CartAuditAccess)roundTrip).avilixlogger$cartState().id),"save/load ID");
            var snapshot=CreateCartAudit.snapshot(entity,null);
            var recovery=CartRecoveryPlan.fromSnapshot(state.id,1,0,"after",snapshot);
            var recoveredItem=net.minecraft.world.item.ItemStack.CODEC.parse(world.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),recovery.item()).getOrThrow();
            check(net.minecraft.world.item.ItemStack.matches(item,recoveredItem),"disaster recovery differs from native packing");
            var recovered=Contraption.fromNBT(world,recoveredItem.get(com.simibubi.create.AllDataComponents.MINECRAFT_CONTRAPTION_DATA),false);
            check(recovered.getBlocks().size()==1&&recovered.getBlocks().get(BlockPos.ZERO).state().is(Blocks.STONE),"recovery lost blocks");
            check(recovered.getStorage().getAllItemStorages().get(BlockPos.ZERO).getStackInSlot(3).getCount()==12,"recovery lost item storage");
            check(recovered.getStorage().getFluids().storages.get(BlockPos.ZERO).getFluidInTank(0).getAmount()==1250,"recovery lost fluid storage");
            exerciseStateRecovery(world,snapshot);
            var staged=new StagedCartRestore(snapshot);int steps=0;
            while(!staged.step(world))check(++steps<100,"restore did not terminate");
            check(staged.entity.getContraption().getBlocks().size()==1,"restored block count");
            check(staged.entity.getContraption().getBlocks().get(BlockPos.ZERO).state().is(Blocks.STONE),"restored block state");
            check(state.id.equals(CreateCartAudit.state(staged.entity).id),"restored persistent ID");
            var restoredStorage=staged.entity.getContraption().getStorage();
            check(restoredStorage.getAllItemStorages().get(BlockPos.ZERO).getStackInSlot(3).getCount()==12,"restored cargo count");
            check(restoredStorage.getFluids().storages.get(BlockPos.ZERO).getFluidInTank(0).getAmount()==1250,"restored fluid amount");
            check(world.getEntity(staged.entity.getUUID())!=staged.entity,"staging published entity prematurely");
            exerciseRollback(event,entity,cart,false);
            exerciseRollback(event,staged.entity,staged.cart,true);
            for(String mode:new String[]{"commit","cancel","logout","conflict"})exerciseRecipients(event,snapshot,mode);
            exerciseRecipientCodec(event);
            exerciseNativeMenu(event,snapshot);
            for(String mode:new String[]{"full","partial","blocked","delay","cancel","conflict","reappear"})exerciseLoot(event,snapshot,mode);
            CreateAutomationSmoke.run(event.getServer(),snapshot);
            CreateCartGuiSmoke.run(event.getServer());
            System.out.println("AVILIX_CREATE_SMOKE_OK identity=pack+save+load restore=detached+incremental+cargo+fluid world=commit+cancel+32-drops recipients=commit+cancel+logout+conflict+strict-codec menu=native-in-place loot=full+partial+blocked+delay+cancel+conflict+reappear");
            event.getServer().halt(false);
        }catch(Throwable failure){
            failure.printStackTrace();System.out.println("AVILIX_CREATE_SMOKE_FAILED");
            event.getServer().halt(false);
        }
    }
    private static void exerciseStateRecovery(net.minecraft.server.level.ServerLevel world,net.minecraft.nbt.CompoundTag snapshot){
        var id=snapshot.getCompound("Entity").getCompound("Contraption").getCompound(CartAuditState.KEY).getUUID("Id");
        var rows=new java.util.ArrayList<com.roften.avilixlogger.core.LogEntry>();var checkpoint=smokeRow(id,1,com.roften.avilixlogger.core.ActionType.CART_PLACE);checkpoint.beAfter=snapshot.toString();rows.add(checkpoint);
        var registries=world.registryAccess();var source=snapshot.copy();
        var before=new net.minecraft.nbt.CompoundTag();before.putString("Kind","item");before.putLong("LocalPos",0);before.putInt("Slot",3);before.put("Item",com.roften.avilixlogger.core.NbtSerde.snapshotItemStack(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,12),registries));
        var after=before.copy();var named=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,5);named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Сохранённый предмет"));after.put("Item",com.roften.avilixlogger.core.NbtSerde.snapshotItemStack(named,registries));
        var change=smokeRow(id,2,com.roften.avilixlogger.core.ActionType.CART_CONTENT_CHANGE);change.beBefore=before.toString();change.beAfter=after.toString();rows.add(change);
        var fluidBefore=new net.minecraft.nbt.CompoundTag();fluidBefore.putString("Kind","fluid");fluidBefore.putLong("LocalPos",0);fluidBefore.putInt("Tank",0);fluidBefore.put("Fluid",new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,1250).save(registries));
        var fluidAfter=fluidBefore.copy();fluidAfter.put("Fluid",new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.LAVA,3000).save(registries));
        change=smokeRow(id,3,com.roften.avilixlogger.core.ActionType.CART_CONTENT_CHANGE);change.beBefore=fluidBefore.toString();change.beAfter=fluidAfter.toString();rows.add(change);
        var controls=new net.minecraft.nbt.CompoundTag();var disabled=new net.minecraft.nbt.ListTag();disabled.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.REDSTONE).save(registries));controls.put("DisabledActors",disabled);
        change=smokeRow(id,4,com.roften.avilixlogger.core.ActionType.CART_STATUS);change.beAfter=controls.toString();rows.add(change);
        var plan=java.util.concurrent.CompletableFuture.supplyAsync(()->{
            check(!com.roften.avilixlogger.core.CartAuditContext.restoring(),"worker started in restore scope");
            var prepared=CartRecoveryPlan.prepare(new SmokeHistory(rows),id,0,registries);
            check(!com.roften.avilixlogger.core.CartAuditContext.restoring(),"replay leaked restore scope");return prepared;
        }).join();check(plan.entry()==4&&plan.snapshotEntry()==1&&plan.changes()==3,"latest state omitted cargo/controls");
        var summary=CartRecoveryContents.validate(plan.item(),registries);check(!com.roften.avilixlogger.core.CartAuditContext.restoring(),"validation leaked restore scope");check(summary.items()==5&&summary.fluidAmount()==3000&&summary.itemStorages()==1&&summary.fluidStorages()==1,"recovery contents summary wrong");
        var item=net.minecraft.world.item.ItemStack.CODEC.parse(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),plan.item()).getOrThrow();
        var restored=Contraption.fromNBT(world,item.get(com.simibubi.create.AllDataComponents.MINECRAFT_CONTRAPTION_DATA),false);
        check(net.minecraft.world.item.ItemStack.matches(restored.getStorage().getAllItemStorages().get(BlockPos.ZERO).getStackInSlot(3),named),"replayed item lost components/count");
        check(net.neoforged.neoforge.fluids.FluidStack.matches(restored.getStorage().getFluids().storages.get(BlockPos.ZERO).getFluidInTank(0),new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.LAVA,3000)),"replayed fluid type/amount lost");
        check(restored.getDisabledActors().size()==1&&restored.getDisabledActors().getFirst().is(net.minecraft.world.item.Items.REDSTONE),"controls not restored");
        var historical=CartRecoveryPlan.prepare(new SmokeHistory(rows),id,2,registries);check(CartRecoveryContents.validate(historical.item(),registries).fluidAmount()==1250,"selected state included future fluid change");
        check(snapshot.equals(source),"forward replay mutated source snapshot");
        var bad=after.copy();bad.getCompound("Item").getCompound("components").putString("missing_mod:component","lost");
        change=smokeRow(id,5,com.roften.avilixlogger.core.ActionType.CART_CONTENT_CHANGE);change.beBefore=after.toString();change.beAfter=bad.toString();rows.add(change);
        recoveryReject(()->CartRecoveryPlan.prepare(new SmokeHistory(rows),id,0,registries),"unknown component accepted");rows.removeLast();
        var broken=after.copy();broken.getCompound("Item").putInt("count",8);
        change=smokeRow(id,5,com.roften.avilixlogger.core.ActionType.CART_CONTENT_CHANGE);change.beBefore=broken.toString();change.beAfter=after.toString();rows.add(change);
        recoveryReject(()->CartRecoveryPlan.prepare(new SmokeHistory(rows),id,0,registries),"broken cargo chain accepted");rows.removeLast();
        var overflow=fluidAfter.copy();overflow.put("Fluid",new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.LAVA,5000).save(registries));
        change=smokeRow(id,5,com.roften.avilixlogger.core.ActionType.CART_CONTENT_CHANGE);change.beBefore=fluidAfter.toString();change.beAfter=overflow.toString();rows.add(change);
        recoveryReject(()->CartRecoveryPlan.prepare(new SmokeHistory(rows),id,0,registries),"over-capacity fluid accepted");rows.removeLast();
        var malformed=plan.item().copy();malformed.getCompound("components").getCompound("create:minecart_contraption_data").getList("items",10).getCompound(0).getCompound("storage").putString("type","missing_mod:storage");
        recoveryReject(()->CartRecoveryContents.validate(malformed,registries),"missing storage silently erased inventory");
        System.out.println("CART_STATE_RECOVERY_NATIVE_OK: latest+selected+item-components+fluid-type+controls+contents-summary+chain-conflict+unknown-codec+capacity; world_changes=0");
    }
    private static void recoveryReject(Runnable task,String message){boolean refused=false;try{task.run();}catch(RuntimeException expected){refused=true;}check(refused,message);}
    private static void exerciseLoot(ServerStartedEvent event,net.minecraft.nbt.CompoundTag checkpoint,String mode) throws Exception {
        var server=event.getServer();var world=server.overworld();var pos=world.getSharedSpawnPos().above(11);
        var built=new StagedCartRestore(checkpoint);while(!built.step(world)){}var current=built.entity;var cart=built.cart;
        cart.setUUID(java.util.UUID.randomUUID());current.setUUID(java.util.UUID.randomUUID());cart.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);current.setPos(cart.position());current.startRiding(cart,true);
        check(world.addFreshEntity(cart)&&world.addFreshEntity(current),"loot fixture source spawn");CreateCartAudit.attach(current);var target=CreateCartAudit.snapshot(current,null);var cartId=CreateCartAudit.state(current).id;
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(world,new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"cart_loot"));
        var field=net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");field.setAccessible(true);
        @SuppressWarnings("unchecked") var players=(java.util.Map<java.util.UUID,net.minecraft.server.level.ServerPlayer>)field.get(server.getPlayerList());players.put(player.getUUID(),player);
        boolean partial=mode.equals("partial")||mode.equals("cancel");
        if(partial||mode.equals("blocked")){for(int i=0;i<41;i++)player.getInventory().setItem(i,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE,64));if(partial)player.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,59));}
        var drop=new net.minecraft.world.entity.item.ItemEntity(world,pos.getX()+.5,pos.getY()+1,pos.getZ()+.5,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,12));drop.getPersistentData().putUUID("AvilixCartDrop",cartId);drop.setNoPickUpDelay();if(mode.equals("delay"))drop.setPickUpDelay(10);check(world.addFreshEntity(drop),"loot fixture drop spawn");
        var rows=new java.util.ArrayList<com.roften.avilixlogger.core.LogEntry>();
        var point=smokeRow(cartId,1,com.roften.avilixlogger.core.ActionType.CART_PLACE);point.beAfter=target.toString();rows.add(point);
        var spawned=smokeRow(cartId,2,com.roften.avilixlogger.core.ActionType.ENTITY_SPAWN);spawned.entityUuid=drop.getUUID();spawned.entityType="minecraft:item";spawned.dim=world.dimension().location().toString();spawned.entityNbt=com.roften.avilixlogger.core.NbtSerde.snapshotEntity(world,drop).toString();spawned.itemStackNbt=com.roften.avilixlogger.core.NbtSerde.snapshotItemStack(drop.getItem(),world.registryAccess()).toString();rows.add(spawned);
        var ctor=CartLootTransferAudit.Scope.class.getDeclaredConstructor(net.minecraft.world.entity.item.ItemEntity.class,net.minecraft.server.level.ServerPlayer.class);ctor.setAccessible(true);
        var capture=(CartLootTransferAudit.Scope)ctor.newInstance(drop,player);drop.playerTouch(player);var pickup=capture.finish(true);
        net.minecraft.world.entity.item.ItemEntity duplicate=null;StagedCartRestore replacement=null;
        try{
            if(mode.equals("blocked")||mode.equals("delay")){check(pickup==null&&!drop.isRemoved()&&drop.getItem().getCount()==12,"refused/delayed pickup created a transfer");return;}
            check(pickup!=null,"successful native pickup was not captured");pickup.materializeSnapshots();pickup.id=3;rows.add(pickup);
            check(pickup.count==(partial?5:12),"native pickup count included unpicked remainder");
            check(com.roften.avilixlogger.core.NbtSerde.fromSnbt(pickup.playerInvAfter).getCompound("Item").isEmpty()!=partial,"removed native item stack was mistaken for remaining loot");
            world.setBlock(pos,Blocks.STONE.defaultBlockState(),3);var block=smokeRow(cartId,4,com.roften.avilixlogger.core.ActionType.BLOCK_PLACE);block.dim=world.dimension().location().toString();block.x=pos.getX();block.y=pos.getY();block.z=pos.getZ();block.blockBefore=net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()).toString();block.blockAfter=net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()).toString();rows.add(block);
            var plan=CartRollbackPlan.prepare(new SmokeHistory(rows),cartId,1);check(plan.spawned().getFirst().item().isEmpty()!=partial,"planner lost native drop remainder");
            if(mode.equals("conflict"))player.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,11));
            if(mode.equals("reappear")){duplicate=new net.minecraft.world.entity.item.ItemEntity(world,pos.getX()+.5,pos.getY()+1,pos.getZ()+.5,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,12));duplicate.setUUID(drop.getUUID());check(world.addFreshEntity(duplicate),"reappearing drop spawn");}
            replacement=new StagedCartRestore(plan.target());Class<?> type=Class.forName("com.roften.avilixlogger.compat.create.CartRollbackCoordinator$Job");var jobCtor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CartRollbackPlan.class,StagedCartRestore.class,net.minecraft.world.entity.Entity.class);jobCtor.setAccessible(true);Object job=jobCtor.newInstance(server.createCommandSourceStack(),plan,replacement,current);
            var step=type.getDeclaredMethod("step",net.minecraft.server.MinecraftServer.class);step.setAccessible(true);
            var phase=type.getDeclaredField("phase");var index=type.getDeclaredField("index");var applied=type.getDeclaredField("applied");var recipientApplied=type.getDeclaredField("recipientApplied");var failure=type.getDeclaredField("failure");for(var f:java.util.List.of(phase,index,applied,recipientApplied,failure))f.setAccessible(true);
            boolean previous=com.roften.avilixlogger.core.CartAuditContext.restoring(true);com.roften.avilixlogger.core.CartAuditContext.rollbackActive=true;
            try{
                int calls=0;boolean interrupted=false,rejected=false;
                while(true){
                    boolean done;try{done=(Boolean)step.invoke(job,server);}catch(java.lang.reflect.InvocationTargetException error){if(!mode.equals("conflict")&&!mode.equals("reappear"))throw error;check(applied.getInt(job)==0&&recipientApplied.getInt(job)==0,"loot conflict modified either side");rejected=true;break;}
                    if(mode.equals("cancel")&&!interrupted&&recipientApplied.getInt(job)>0){phase.setInt(job,4);index.setInt(job,applied.getInt(job)-1);failure.set(job,"fixture loot cancellation");interrupted=true;}
                    if(done)break;check(++calls<250,"loot rollback did not terminate");
                }
                if(mode.equals("cancel")){check(world.getBlockState(pos).is(Blocks.STONE)&&!drop.isRemoved()&&drop.getItem().getCount()==7&&player.getInventory().getItem(0).getCount()==64&&!current.isRemoved(),"loot compensation did not restore both sides");}
                else if(mode.equals("conflict")||mode.equals("reappear")){check(rejected&&world.getBlockState(pos).is(Blocks.STONE)&&!current.isRemoved(),"unsafe loot rollback committed");}
                else{check(world.getBlockState(pos).isAir()&&drop.isRemoved()&&current.isRemoved(),"loot rollback left original effects");check(partial?player.getInventory().getItem(0).getCount()==59:player.getInventory().getItem(0).isEmpty(),"loot rollback duplicated picked items");}
            }finally{com.roften.avilixlogger.core.CartAuditContext.restoring(previous);com.roften.avilixlogger.core.CartAuditContext.rollbackActive=false;}
        }finally{
            players.remove(player.getUUID());com.roften.avilixlogger.core.CartRestoreLocks.clear();com.roften.avilixlogger.core.CartAuditContext.LOCKED_ENTITIES.clear();
            if(!drop.isRemoved())drop.discard();if(duplicate!=null&&!duplicate.isRemoved())duplicate.discard();if(!current.isRemoved())((CartEntityAccess)current).avilixlogger$discardForRollback();if(!cart.isRemoved())cart.discard();
            if(replacement!=null){if(replacement.entity!=null&&!replacement.entity.isRemoved())((CartEntityAccess)replacement.entity).avilixlogger$discardForRollback();if(replacement.cart!=null&&!replacement.cart.isRemoved())replacement.cart.discard();}world.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
        }
    }
    private static com.roften.avilixlogger.core.LogEntry smokeRow(java.util.UUID cart,long id,com.roften.avilixlogger.core.ActionType type){var row=new com.roften.avilixlogger.core.LogEntry();row.id=id;row.ts=System.currentTimeMillis();row.type=type;row.source=com.roften.avilixlogger.core.CartAuditContext.prefix(cart)+"work:0";return row;}
    private record SmokeHistory(java.util.List<com.roften.avilixlogger.core.LogEntry> rows) implements com.roften.avilixlogger.core.LogStorage {
        public void append(com.roften.avilixlogger.core.LogEntry row){rows.add(row);}
        public java.util.List<com.roften.avilixlogger.core.LogEntry> query(com.roften.avilixlogger.core.LogQuery q){return rows.stream().filter(e->e.id>q.afterId).sorted(java.util.Comparator.comparingLong(e->e.id)).limit(q.limit).toList();}
        public java.util.List<com.roften.avilixlogger.core.LogEntry> queryReverse(com.roften.avilixlogger.core.LogQuery q){return rows.stream().filter(e->q.beforeId<=0||e.id<q.beforeId).sorted(java.util.Comparator.<com.roften.avilixlogger.core.LogEntry>comparingLong(e->e.id).reversed()).limit(q.limit).toList();}
        public void shutdown(){}
    }
    private static void exerciseRecipientCodec(ServerStartedEvent event) throws Exception {
        var registries=event.getServer().registryAccess();
        var read=CartRollbackCoordinator.class.getDeclaredMethod("readRecipientItem",net.minecraft.nbt.CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class);read.setAccessible(true);
        var valid=com.roften.avilixlogger.core.NbtSerde.snapshotItemStack(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND),registries);
        check(((net.minecraft.world.item.ItemStack)read.invoke(null,valid,registries)).getCount()==1,"native default item count rejected");
        for(String component:new String[]{"missing_mod:lost_component","minecraft:damage"}){
            var malformed=valid.copy();var components=new net.minecraft.nbt.CompoundTag();components.putString(component,"invalid");malformed.put("components",components);
            boolean rejected=false;
            try{read.invoke(null,malformed,registries);}catch(java.lang.reflect.InvocationTargetException failure){check(failure.getCause() instanceof IllegalStateException,"unexpected item decode failure");rejected=true;}
            check(rejected,"rollback accepted incomplete component: "+component);
        }
    }
    private static void exerciseRecipients(ServerStartedEvent event,net.minecraft.nbt.CompoundTag checkpoint,String mode) throws Exception {
        var server=event.getServer();var world=server.overworld();var built=new StagedCartRestore(checkpoint);while(!built.step(world)){}
        var current=built.entity;var cart=built.cart;var pos=world.getSharedSpawnPos().above(8);
        cart.setUUID(java.util.UUID.randomUUID());current.setUUID(java.util.UUID.randomUUID());cart.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);current.setPos(cart.position());current.startRiding(cart,true);
        check(world.addFreshEntity(cart)&&world.addFreshEntity(current),"recipient source spawn");CreateCartAudit.attach(current);
        var target=CreateCartAudit.snapshot(current,null);
        current.getContraption().getStorage().getAllItemStorages().get(BlockPos.ZERO).setStackInSlot(3,net.minecraft.world.item.ItemStack.EMPTY);
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(world,new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"cart_recipient"));
        var field=net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");field.setAccessible(true);
        @SuppressWarnings("unchecked") var map=(java.util.Map<java.util.UUID,net.minecraft.server.level.ServerPlayer>)field.get(server.getPlayerList());map.put(player.getUUID(),player);
        player.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,mode.equals("conflict")?11:12));
        var after=com.roften.avilixlogger.core.NbtSerde.snapshotItemStack(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,12),world.registryAccess());
        var recipient=new CartMenuTransferPlan.SlotUndo(player.getUUID(),0,new net.minecraft.nbt.CompoundTag(),after);
        world.setBlock(pos,Blocks.STONE.defaultBlockState(),3);
        var undo=new CartRollbackPlan.BlockUndo(world.dimension().location().toString(),pos,"","",net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()),net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()),null,null,null,null);
        var plan=new CartRollbackPlan(CreateCartAudit.state(current).id,1,2,1,target,target,java.util.List.of(undo),java.util.List.of(),java.util.List.of(),java.util.List.of(recipient),target.sizeInBytes());
        var replacement=new StagedCartRestore(target);
        Class<?> type=Class.forName("com.roften.avilixlogger.compat.create.CartRollbackCoordinator$Job");
        var ctor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CartRollbackPlan.class,StagedCartRestore.class,net.minecraft.world.entity.Entity.class);ctor.setAccessible(true);
        Object job=ctor.newInstance(server.createCommandSourceStack(),plan,replacement,current);
        var step=type.getDeclaredMethod("step",net.minecraft.server.MinecraftServer.class);step.setAccessible(true);
        var phase=type.getDeclaredField("phase");var index=type.getDeclaredField("index");var applied=type.getDeclaredField("applied");var recipientApplied=type.getDeclaredField("recipientApplied");var failure=type.getDeclaredField("failure");
        for(var f:java.util.List.of(phase,index,applied,recipientApplied,failure))f.setAccessible(true);
        var active=CartRollbackCoordinator.class.getDeclaredField("active");active.setAccessible(true);Object previousActive=active.get(null);active.set(null,job);
        boolean previous=com.roften.avilixlogger.core.CartAuditContext.restoring(true);com.roften.avilixlogger.core.CartAuditContext.rollbackActive=true;
        try {
            boolean testedLock=false,interrupted=false,conflict=false;int calls=0;
            while(true){
                boolean done;
                try{done=(Boolean)step.invoke(job,server);}catch(java.lang.reflect.InvocationTargetException e){if(!mode.equals("conflict"))throw e;check(applied.getInt(job)==0&&recipientApplied.getInt(job)==0,"conflict modified world or recipient");conflict=true;break;}
                if(!testedLock && phase.getInt(job)==2){
                    boolean restore=com.roften.avilixlogger.core.CartAuditContext.restoring(false);
                    try{player.getInventory().setItem(0,net.minecraft.world.item.ItemStack.EMPTY);check(player.getInventory().getItem(0).getCount()==12,"native recipient lock missing");check(player.getInventory().removeItem(0,1).isEmpty(),"native recipient extraction not locked");check(player.getInventory().clearOrCountMatchingItems(stack->true,-1,player.inventoryMenu.getCraftSlots())==0&&player.getInventory().getItem(0).getCount()==12,"native clear bypassed reservation");}
                    finally{com.roften.avilixlogger.core.CartAuditContext.restoring(restore);}testedLock=true;
                }
                if(!interrupted && recipientApplied.getInt(job)>0 && !mode.equals("commit")){
                    if(mode.equals("logout")){CartRollbackCoordinator.playerLeaving(player);check(player.getInventory().getItem(0).getCount()==12,"logout saved already-undone recipient");map.remove(player.getUUID());}
                    else{phase.setInt(job,4);index.setInt(job,applied.getInt(job)-1);failure.set(job,"fixture recipient cancellation");}
                    interrupted=true;
                }
                if(done)break;check(++calls<200,"recipient rollback did not terminate");
            }
            if(mode.equals("commit")){check(world.getBlockState(pos).isAir()&&player.getInventory().getItem(0).isEmpty(),"recipient/world commit incomplete");check(replacement.entity.getContraption().getStorage().getAllItemStorages().get(BlockPos.ZERO).getStackInSlot(3).getCount()==12,"committed cargo missing");check(current.isRemoved()&&cart.isRemoved(),"recipient commit left original cart");}
            else{check(world.getBlockState(pos).is(Blocks.STONE)&&!current.isRemoved(),"recipient cancellation lost original world/cart");check(player.getInventory().getItem(0).getCount()==(mode.equals("conflict")?11:12),"recipient compensation lost items");}
            if(mode.equals("conflict"))check(conflict,"changed recipient accepted");
        }finally{
            active.set(null,previousActive);map.remove(player.getUUID());com.roften.avilixlogger.core.CartRestoreLocks.clear();com.roften.avilixlogger.core.CartAuditContext.LOCKED_ENTITIES.clear();com.roften.avilixlogger.core.CartAuditContext.rollbackActive=false;com.roften.avilixlogger.core.CartAuditContext.restoring(previous);
            if(!current.isRemoved())((CartEntityAccess)current).avilixlogger$discardForRollback();if(!cart.isRemoved())cart.discard();if(replacement.entity!=null&&!replacement.entity.isRemoved())((CartEntityAccess)replacement.entity).avilixlogger$discardForRollback();if(replacement.cart!=null&&!replacement.cart.isRemoved())replacement.cart.discard();world.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
        }
    }
    private static void exerciseNativeMenu(ServerStartedEvent event,net.minecraft.nbt.CompoundTag checkpoint) throws Exception {
        var world=event.getServer().overworld();var staged=new StagedCartRestore(checkpoint);while(!staged.step(world)){}
        var entity=staged.entity;CartStorageAudit.bind(entity);var handler=entity.getContraption().getStorage().getAllItemStorages().get(BlockPos.ZERO);
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(world,new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"cart_menu"));
        var container=new com.simibubi.create.api.contraption.storage.item.menu.StorageInteractionWrapper(handler,p->true,p->{});
        var menu=new net.minecraft.world.inventory.ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x1,1,player.getInventory(),container,1);player.containerMenu=menu;
        var ref=new CartStorageAudit.Ref(new java.lang.ref.WeakReference<>(entity),BlockPos.ZERO,false);
        var ctor=CartMenuTransferAudit.Scope.class.getDeclaredConstructor(net.minecraft.server.level.ServerPlayer.class,CartStorageAudit.Ref.class);ctor.setAccessible(true);var scope=ctor.newInstance(player,ref);
        menu.clicked(3,0,net.minecraft.world.inventory.ClickType.PICKUP,player);
        check(menu.getCarried().getCount()==12&&handler.getStackInSlot(3).isEmpty(),"native menu did not move stack");scope.finish(true);
        var changes=CartMenuTransferAudit.Scope.class.getDeclaredField("changes");changes.setAccessible(true);check(((java.util.List<?>)changes.get(scope)).size()==1,"in-place split was not captured by menu snapshots");
        var complete=CartMenuTransferAudit.Scope.class.getDeclaredField("complete");complete.setAccessible(true);check(complete.getBoolean(scope),"standard Create menu address not resolved");
        var closingCtor=CartMenuTransferAudit.Scope.class.getDeclaredConstructor(net.minecraft.server.level.ServerPlayer.class,CartStorageAudit.Ref.class,boolean.class);closingCtor.setAccessible(true);
        var closing=closingCtor.newInstance(player,ref,true);player.closeContainer();closing.finish(true,true);
        check(menu.getCarried().isEmpty()&&player.getInventory().getItem(0).getCount()==12,"native close did not return cursor stack");check(complete.getBoolean(closing),"native menu closure not fully captured");
        player.containerMenu=player.inventoryMenu;
    }
    private static void exerciseRollback(ServerStartedEvent event,OrientedContraptionEntity current,AbstractMinecart currentCart,boolean cancel) throws Exception {
        var world=event.getServer().overworld();var pos=world.getSharedSpawnPos().above(5);
        currentCart.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);current.setPos(currentCart.position());
        if(current.getVehicle()!=currentCart)current.startRiding(currentCart,true);
        check(world.addFreshEntity(currentCart),"source cart spawn");check(world.addFreshEntity(current),"source contraption spawn");CreateCartAudit.attach(current);
        var target=CreateCartAudit.snapshot(current,null);
        world.setBlock(pos,Blocks.STONE.defaultBlockState(),3);
        var state=CreateCartAudit.state(current);state.locked=true;
        var undo=new CartRollbackPlan.BlockUndo(world.dimension().location().toString(),pos,"","",net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()),net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()),null,null,null,null);
        var drops=new java.util.ArrayList<net.minecraft.world.entity.item.ItemEntity>();var effects=new java.util.ArrayList<CartRollbackPlan.SpawnUndo>();
        for(int i=0;i<32;i++){
            var drop=new net.minecraft.world.entity.item.ItemEntity(world,pos.getX()+.5,pos.getY()+2,pos.getZ()+.5,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE,i+1));check(world.addFreshEntity(drop),"fixture output spawn");drops.add(drop);
            effects.add(new CartRollbackPlan.SpawnUndo(world.dimension().location().toString(),drop.getUUID(),"minecraft:item",com.roften.avilixlogger.core.NbtSerde.snapshotEntity(world,drop),com.roften.avilixlogger.core.NbtSerde.snapshotItemStack(drop.getItem(),world.registryAccess())));
        }
        var plan=new CartRollbackPlan(state.id,1,2,1,target,target,java.util.List.of(undo),java.util.List.of(),effects,java.util.List.of(),target.sizeInBytes());
        var replacement=new StagedCartRestore(target);
        Class<?> type=Class.forName("com.roften.avilixlogger.compat.create.CartRollbackCoordinator$Job");
        var ctor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CartRollbackPlan.class,StagedCartRestore.class,net.minecraft.world.entity.Entity.class);ctor.setAccessible(true);
        Object job=ctor.newInstance(event.getServer().createCommandSourceStack(),plan,replacement,current);
        var step=type.getDeclaredMethod("step",net.minecraft.server.MinecraftServer.class);step.setAccessible(true);
        java.lang.reflect.Field phase=type.getDeclaredField("phase"),index=type.getDeclaredField("index"),applied=type.getDeclaredField("applied"),failure=type.getDeclaredField("failure");
        phase.setAccessible(true);index.setAccessible(true);applied.setAccessible(true);failure.setAccessible(true);
        boolean previous=com.roften.avilixlogger.core.CartAuditContext.restoring(true);com.roften.avilixlogger.core.CartAuditContext.rollbackActive=true;
        try {
            int calls=0;
            while(!(Boolean)step.invoke(job,event.getServer())) {
                check(++calls<500,"world rollback did not terminate");
                if(cancel && applied.getInt(job)>0 && phase.getInt(job)==3){phase.setInt(job,4);index.setInt(job,applied.getInt(job)-1);failure.set(job,"fixture cancellation");}
            }
            if(cancel){check(world.getBlockState(pos).is(Blocks.STONE),"cancel did not compensate world");check(!current.isRemoved()&&!currentCart.isRemoved(),"cancel consumed source");check(drops.stream().noneMatch(net.minecraft.world.entity.Entity::isRemoved),"cancel consumed drill output");}
            else {
                check(world.getBlockState(pos).isAir(),"world undo not applied");check(current.isRemoved()&&currentCart.isRemoved(),"commit left source copy");
                var result=CreateCartAudit.loaded(state.id);check(result==replacement.entity,"committed representation not indexed");
                check(replacement.entity.getVehicle()==replacement.cart,"committed cart lost passenger");
                check(drops.stream().allMatch(net.minecraft.world.entity.Entity::isRemoved),"rollback duplicated drill output");
            }
        }finally{
            com.roften.avilixlogger.core.CartRestoreLocks.clear();com.roften.avilixlogger.core.CartAuditContext.rollbackActive=false;com.roften.avilixlogger.core.CartAuditContext.restoring(previous);
            com.roften.avilixlogger.core.CartAuditContext.LOCKED_ENTITIES.clear();for(var drop:drops)if(!drop.isRemoved())drop.discard();
            if(!current.isRemoved())((CartEntityAccess)current).avilixlogger$discardForRollback();if(!currentCart.isRemoved())currentCart.discard();
            if(replacement.entity!=null&&!replacement.entity.isRemoved())((CartEntityAccess)replacement.entity).avilixlogger$discardForRollback();if(replacement.cart!=null&&!replacement.cart.isRemoved())replacement.cart.discard();
            world.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
        }
    }
    private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
}

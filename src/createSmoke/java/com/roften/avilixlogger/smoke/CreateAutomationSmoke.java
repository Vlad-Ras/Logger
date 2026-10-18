package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.actors.psi.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.fluids.*;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import java.lang.reflect.*;
import java.util.*;

/** Real native PSI/capability transfers and rollback; detached capture never enables AuthCore. */
public final class CreateAutomationSmoke {
    public static void run(MinecraftServer server,CompoundTag checkpoint)throws Exception{
        var decoder=CartRollbackCoordinator.class.getDeclaredMethod("readAutomationFluid",CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class);decoder.setAccessible(true);
        var sample=(CompoundTag)new FluidStack(Fluids.WATER,750).save(server.registryAccess());
        check(((FluidStack)decoder.invoke(null,sample,server.registryAccess())).getAmount()==750,"native fluid codec round trip");
        for(String component:List.of("missingmod:unknown","minecraft:damage")){var bad=sample.copy();var components=new CompoundTag();components.putString(component,"invalid");bad.put("components",components);boolean rejected=false;try{decoder.invoke(null,bad,server.registryAccess());}catch(InvocationTargetException expected){rejected=true;}check(rejected,"fluid partial component decode accepted");}
        for(String kind:List.of("item","fluid"))for(String mode:List.of("out","in","partial","cancel","conflict","replace","unloaded","lock"))exercise(server,checkpoint,kind,mode,0);
        for(int slot:List.of(0,27))for(String mode:List.of("out","in","partial","cancel","conflict","replace","unloaded","lock","partner","split","alias","partner-history"))exercise(server,checkpoint,"double",mode,slot);
        exercise(server,checkpoint,"trapped","alias",27);
        System.out.println("CREATE_AUTOMATION_NATIVE_SMOKE_OK: 41 scenarios; PSI items/fluids and both double-chest halves, both directions, partial capacity, compensation, components, replacement/split/unloaded/history conflicts, canonical aliases and fresh/manual-wrapper locks");
    }
    private static void exercise(MinecraftServer server,CompoundTag checkpoint,String kind,String mode,int slot)throws Exception{
        ServerLevel world=server.overworld();BlockPos origin=world.getSharedSpawnPos().above(14);BlockPos machinePos=origin.offset(4,0,0),externalPos=origin.offset(2,0,0),psiPos=origin,worldPos=origin.offset(8,0,0);
        boolean isItem=!kind.equals("fluid"),doubleChest=kind.equals("double")||kind.equals("trapped");
        BlockPos partnerPos=externalPos.west();
        var built=new StagedCartRestore(checkpoint);while(!built.step(world)){}var current=built.entity;var cart=built.cart;
        current.setUUID(UUID.randomUUID());cart.setUUID(UUID.randomUUID());cart.setPos(origin.getX()+.5,origin.getY()+2,origin.getZ()+.5);current.setPos(cart.position());current.startRiding(cart,true);
        check(world.addFreshEntity(cart)&&world.addFreshEntity(current),"automation source spawn");CreateCartAudit.attach(current);CreateCartAudit.state(current).locked=false;
        var target=CreateCartAudit.snapshot(current,null);UUID id=CreateCartAudit.state(current).id;
        world.setBlock(machinePos.below(),Blocks.STONE.defaultBlockState(),3);world.setBlock(machinePos,AllBlocks.ANDESITE_FUNNEL.getDefaultState(),3);
        var chest=kind.equals("trapped")?Blocks.TRAPPED_CHEST:Blocks.CHEST;
        world.setBlock(externalPos,isItem?chest.defaultBlockState():AllBlocks.FLUID_TANK.getDefaultState(),3);
        if(doubleChest){
            world.setBlock(partnerPos,chest.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.LEFT),2);
            world.setBlock(externalPos,chest.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.RIGHT),2);
        }
        world.setBlock(psiPos,isItem?AllBlocks.PORTABLE_STORAGE_INTERFACE.getDefaultState():AllBlocks.PORTABLE_FLUID_INTERFACE.getDefaultState(),3);
        var machine=world.getBlockEntity(machinePos);check(machine!=null,"native machine missing");
        var psi=(PortableStorageInterfaceBlockEntity)world.getBlockEntity(psiPos);psi.startTransferringTo(current.getContraption(),1);
        var timer=PortableStorageInterfaceBlockEntity.class.getDeclaredField("transferTimer");timer.setAccessible(true);timer.setInt(psi,6);check(psi.canTransfer(),"native interface not connected");
        IItemHandler items=isItem?world.getCapability(Capabilities.ItemHandler.BLOCK,externalPos,null):null;
        IFluidHandler fluids=kind.equals("fluid")?world.getCapability(Capabilities.FluidHandler.BLOCK,externalPos,null):null;
        if(items!=null)((net.neoforged.neoforge.items.IItemHandlerModifiable)items).setStackInSlot(slot,new ItemStack(Items.DIAMOND,mode.equals("partial")?60:mode.equals("in")?12:0));
        if(doubleChest)check(items.getSlots()==54,"double chest not formed");
        if(doubleChest){
            var reversed=new net.neoforged.neoforge.items.wrapper.InvWrapper(new net.minecraft.world.CompoundContainer((net.minecraft.world.Container)world.getBlockEntity(partnerPos),(net.minecraft.world.Container)world.getBlockEntity(externalPos)));
            check(CartAutomationStorage.describe(world,externalPos,"none","item",slot,reversed)==null,"reversed custom compound accepted as native chest");
        }
        if(fluids!=null){int initial=mode.equals("partial")?fluids.getTankCapacity(0)-100:mode.equals("in")?800:100;fluids.fill(new FluidStack(Fluids.WATER,initial),IFluidHandler.FluidAction.EXECUTE);}
        int beforeExternal=items!=null?items.getStackInSlot(slot).getCount():fluids.getFluidInTank(0).getAmount();
        var itemCache=isItem?BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK,world,externalPos,null):null;
        var fluidCache=kind.equals("fluid")?BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK,world,externalPos,null):null;
        // Warm the cache outside capture: a cache hit must still address the native handler.
        if(itemCache!=null)itemCache.getCapability();if(fluidCache!=null)fluidCache.getCapability();
        var scopeCtor=CartAutomationAudit.Scope.class.getDeclaredConstructor();scopeCtor.setAccessible(true);var scope=scopeCtor.newInstance();
        var reset=CartAutomationAudit.Scope.class.getDeclaredMethod("reset",net.minecraft.world.level.block.entity.BlockEntity.class);reset.setAccessible(true);reset.invoke(scope,machine);
        var active=CartAutomationAudit.class.getDeclaredField("current");active.setAccessible(true);active.set(null,scope);
        var rowsMethod=CartAutomationAudit.Scope.class.getDeclaredMethod("rows",boolean.class);rowsMethod.setAccessible(true);
        var cargo=CartAutomationAudit.Scope.class.getDeclaredMethod("cargo",CartStorageAudit.Ref.class,CompoundTag.class,CompoundTag.class,String.class);cargo.setAccessible(true);
        List<LogEntry> captured;
        try{
            // Resolve inside the operation so the actual native capability/cache hooks supply addresses.
            items=itemCache==null?null:itemCache.getCapability();
            fluids=fluidCache==null?null:fluidCache.getCapability();
            var ref=new CartStorageAudit.Ref(new java.lang.ref.WeakReference<>(current),BlockPos.ZERO,false);
            if(items!=null){
                IItemHandler port=world.getCapability(Capabilities.ItemHandler.BLOCK,psiPos,null);check(CartStorageAudit.owner(port)==current,"portable item alias missing");
                var mounted=current.getContraption().getStorage().getAllItemStorages().get(BlockPos.ZERO);ItemStack before=mounted.getStackInSlot(3).copy();
                port.extractItem(3,4,true);items.insertItem(slot,new ItemStack(Items.DIAMOND,4),true);
                @SuppressWarnings("unchecked")var empty=(List<LogEntry>)rowsMethod.invoke(scope,true);check(empty.isEmpty(),"simulation produced automation rows");
                if(mode.equals("in")){ItemStack moved=items.extractItem(slot,4,false);check(moved.getCount()==4&&port.insertItem(3,moved,false).isEmpty(),"native inbound remainder");}
                else{int amount=mode.equals("partial")?4:12;ItemStack moved=port.extractItem(3,amount,false);check(moved.getCount()==amount,"native outbound amount");
                    if(mode.equals("alias")){check(items.insertItem(slot,moved.split(5),false).isEmpty(),"first alias insert");var other=world.getCapability(Capabilities.ItemHandler.BLOCK,partnerPos,Direction.UP);check(other.insertItem(slot,moved,false).isEmpty(),"second alias insert");}
                    else check(items.insertItem(slot,moved,false).isEmpty(),"native outbound remainder");}
                cargo.invoke(scope,ref,CartStorageAudit.item(current,BlockPos.ZERO,3,before),CartStorageAudit.item(current,BlockPos.ZERO,3,mounted.getStackInSlot(3)),"fixture native item transfer");
            }else{
                var port=world.getCapability(Capabilities.FluidHandler.BLOCK,psiPos,null);check(CartStorageAudit.owner(port)==current,"portable fluid alias missing");
                var mounted=current.getContraption().getStorage().getFluids().storages.get(BlockPos.ZERO);FluidStack before=mounted.getFluidInTank(0).copy();
                port.drain(750,IFluidHandler.FluidAction.SIMULATE);fluids.fill(new FluidStack(Fluids.WATER,100),IFluidHandler.FluidAction.SIMULATE);
                @SuppressWarnings("unchecked")var empty=(List<LogEntry>)rowsMethod.invoke(scope,true);check(empty.isEmpty(),"fluid simulation produced automation rows");
                int amount=mode.equals("partial")?100:750;
                if(mode.equals("in"))check(port.fill(fluids.drain(amount,IFluidHandler.FluidAction.EXECUTE),IFluidHandler.FluidAction.EXECUTE)==amount,"native inbound fluid amount");
                else check(fluids.fill(port.drain(amount,IFluidHandler.FluidAction.EXECUTE),IFluidHandler.FluidAction.EXECUTE)==amount,"native outbound fluid amount");
                cargo.invoke(scope,ref,fluid(world,before),fluid(world,mounted.getFluidInTank(0)),"fixture native fluid transfer");
            }
            @SuppressWarnings("unchecked")var result=(List<LogEntry>)rowsMethod.invoke(scope,true);captured=result;
        }finally{active.set(null,null);}
        StagedCartRestore replacement=null;
        try{
            check(captured.size()==2,"native automation operation was duplicated/incomplete");
            var history=new ArrayList<LogEntry>();var point=row(id,1,ActionType.CART_PLACE);point.beAfter=target.toString();history.add(point);long seq=2;for(var r:captured){r.materializeSnapshots();r.id=seq++;history.add(r);}
            var manifest=NbtSerde.fromSnbt(captured.getLast().beAfter);check(manifest.getBoolean("Complete")&&manifest.getList("Slots",10).size()==1,"native recipient missing: "+kind+"/"+mode+" handler="+(items!=null?items.getClass().getName():fluids.getClass().getName())+" "+manifest);check(captured.getLast().actorUuid==null&&captured.getLast().actorName.equals("SYSTEM"),"machine blamed backpack/cart owner");
            check(CartLogDetails.describe(world,captured.getLast()).stream().anyMatch(c->c.getString().contains("Хранилище:")),"GUI omitted automatic endpoint address");
            check(CartLogDetails.describe(world,captured.getFirst()).stream().anyMatch(c->c.getString().contains("До:")),"GUI omitted cargo before/after");
            if(doubleChest){var address=manifest.getList("Slots",10).getCompound(0);check(address.getLong("Pos")==externalPos.asLong()&&address.getCompound("DoubleChest").getLong("Pos")==partnerPos.asLong()&&address.getInt("Slot")==slot,"double-chest address not canonical");check(CartLogDetails.describe(world,captured.getLast()).stream().anyMatch(c->c.getString().contains("Двойной сундук:")),"GUI omitted double chest");}
            if(mode.equals("unloaded"))for(var r:captured)if(r.type==ActionType.CART_TRANSFER){var b=NbtSerde.fromSnbt(r.beBefore);var a=NbtSerde.fromSnbt(r.beAfter);for(var n:List.of(b,a))n.getList("Slots",10).getCompound(0).putLong("Pos",new BlockPos(1_000_000,100,1_000_000).asLong());r.beBefore=b.toString();r.beAfter=a.toString();}
            world.setBlock(worldPos,Blocks.STONE.defaultBlockState(),3);var block=row(id,seq,ActionType.BLOCK_PLACE);block.dim=world.dimension().location().toString();block.x=worldPos.getX();block.y=worldPos.getY();block.z=worldPos.getZ();block.blockBefore=NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()).toString();block.blockAfter=NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()).toString();history.add(block);
            if(mode.equals("partner-history")){block.x=partnerPos.getX();block.y=partnerPos.getY();block.z=partnerPos.getZ();}
            var plan=CartRollbackPlan.prepare(new History(history),id,1);check(plan.automation().size()==1,"native automation plan lost endpoint");
            if(mode.equals("partner-history")){var prepare=CartRollbackCoordinator.class.getDeclaredMethod("prepareAutomation",CartRollbackPlan.class,net.minecraft.core.HolderLookup.Provider.class);prepare.setAccessible(true);boolean denied=false;try{prepare.invoke(null,plan,world.registryAccess());}catch(InvocationTargetException expected){denied=true;}check(denied,"partner block history bypassed external-order guard");return;}
            if(mode.equals("conflict")){if(items!=null){var changed=items.getStackInSlot(slot).copy();changed.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("changed"));((net.neoforged.neoforge.items.IItemHandlerModifiable)items).setStackInSlot(slot,changed);}else fluids.drain(1,IFluidHandler.FluidAction.EXECUTE);}
            if(mode.equals("replace"))world.setBlock(externalPos,Blocks.BARREL.defaultBlockState(),3);
            if(mode.equals("partner"))world.setBlock(partnerPos,Blocks.BARREL.defaultBlockState(),3);
            if(mode.equals("split"))world.setBlock(partnerPos,Blocks.AIR.defaultBlockState(),3);
            replacement=new StagedCartRestore(plan.target());Class<?> type=Class.forName("com.roften.avilixlogger.compat.create.CartRollbackCoordinator$Job");var ctor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CartRollbackPlan.class,StagedCartRestore.class,net.minecraft.world.entity.Entity.class);ctor.setAccessible(true);Object job=ctor.newInstance(server.createCommandSourceStack(),plan,replacement,current);
            var step=type.getDeclaredMethod("step",MinecraftServer.class);step.setAccessible(true);var phase=type.getDeclaredField("phase");var index=type.getDeclaredField("index");var applied=type.getDeclaredField("applied");var changed=type.getDeclaredField("automationApplied");var failure=type.getDeclaredField("failure");for(var f:List.of(phase,index,applied,changed,failure))f.setAccessible(true);
            boolean old=CartAuditContext.restoring(true);CartAuditContext.rollbackActive=true;
            try{
                boolean interrupted=false,rejected=false,lockChecked=false;int calls=0;
                while(true){
                    boolean done;try{done=(Boolean)step.invoke(job,server);}catch(InvocationTargetException error){if(!Set.of("conflict","replace","unloaded","partner","split").contains(mode))throw error;check(applied.getInt(job)==0&&changed.getInt(job)==0,"endpoint conflict changed world/source");rejected=true;break;}
                    if(mode.equals("lock")&&!lockChecked&&phase.getInt(job)==2){CartAuditContext.restoring(false);try{if(items!=null){var fresh=world.getCapability(Capabilities.ItemHandler.BLOCK,doubleChest?partnerPos:externalPos,null);check(fresh.extractItem(slot,1,false).isEmpty(),"fresh chest wrapper bypassed lock");
                        if(doubleChest){var manual=new net.neoforged.neoforge.items.wrapper.InvWrapper(new net.minecraft.world.CompoundContainer((net.minecraft.world.Container)world.getBlockEntity(externalPos),(net.minecraft.world.Container)world.getBlockEntity(partnerPos)));check(manual.extractItem(slot,1,false).isEmpty(),"manual combined wrapper bypassed lock");}
                    }else{var fresh=world.getCapability(Capabilities.FluidHandler.BLOCK,externalPos,null);check(fresh.drain(1,IFluidHandler.FluidAction.EXECUTE).isEmpty(),"tank bypassed lock");}}finally{CartAuditContext.restoring(true);}lockChecked=true;}
                    if(mode.equals("cancel")&&!interrupted&&changed.getInt(job)>0){phase.setInt(job,4);index.setInt(job,applied.getInt(job)-1);failure.set(job,"fixture automatic cancellation");interrupted=true;}
                    if(done)break;check(++calls<300,"automation rollback did not terminate");
                }
                if(Set.of("conflict","replace","unloaded","partner","split").contains(mode))check(rejected&&!current.isRemoved()&&world.getBlockState(worldPos).is(Blocks.STONE),"unsafe automation rollback committed");
                else if(mode.equals("cancel")){check(!current.isRemoved()&&world.getBlockState(worldPos).is(Blocks.STONE),"automation cancellation lost world/source");check((items!=null?items.getStackInSlot(slot).getCount():fluids.getFluidInTank(0).getAmount())==beforeExternal+(isItem?12:750),"automatic recipient not compensated");}
                else{check(current.isRemoved()&&world.getBlockState(worldPos).isAir(),"automatic rollback left old source/world");check((items!=null?items.getStackInSlot(slot).getCount():fluids.getFluidInTank(0).getAmount())==beforeExternal,"automatic recipient duplicated/lost cargo");if(mode.equals("lock"))check(lockChecked,"reservation check not exercised");}
            }finally{CartAuditContext.restoring(old);CartAuditContext.rollbackActive=false;}
        }finally{
            CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();if(!current.isRemoved())((CartEntityAccess)current).avilixlogger$discardForRollback();if(!cart.isRemoved())cart.discard();
            if(replacement!=null){if(replacement.entity!=null&&!replacement.entity.isRemoved())((CartEntityAccess)replacement.entity).avilixlogger$discardForRollback();if(replacement.cart!=null&&!replacement.cart.isRemoved())replacement.cart.discard();}
            for(var pos:List.of(machinePos,externalPos,psiPos,worldPos,machinePos.below()))world.setBlock(pos,Blocks.AIR.defaultBlockState(),3);CartAutomationAudit.clear();
            if(doubleChest)world.setBlock(partnerPos,Blocks.AIR.defaultBlockState(),3);
        }
    }
    private static CompoundTag fluid(ServerLevel world,FluidStack stack){var n=new CompoundTag();n.putString("Kind","fluid");n.putBoolean("External",true);n.putLong("LocalPos",0);n.putInt("Tank",0);if(!stack.isEmpty())n.put("Fluid",stack.save(world.registryAccess()));return n;}
    private static LogEntry row(UUID id,long seq,ActionType type){var r=new LogEntry();r.id=seq;r.type=type;r.ts=System.currentTimeMillis();r.source=CartAuditContext.prefix(id)+"work:1";return r;}
    private record History(List<LogEntry> rows)implements LogStorage{public void append(LogEntry r){}public List<LogEntry> query(LogQuery q){return rows.stream().filter(r->r.id>q.afterId).limit(q.limit).toList();}public List<LogEntry> queryReverse(LogQuery q){return rows.stream().filter(r->r.id<q.beforeId).sorted(Comparator.<LogEntry>comparingLong(r->r.id).reversed()).limit(q.limit).toList();}public void shutdown(){}}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}

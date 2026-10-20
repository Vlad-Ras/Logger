package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import java.lang.reflect.*;
import java.util.*;

/** Real mounted storage mutations plus the production multi-endpoint Job; no auth override. */
public final class CreateLinkedTransferSmoke {
    private static final BlockPos TANK=new BlockPos(1,0,1);
    public static void run(ServerLevel world)throws Exception{
        var factory=CreateCargoSmoke.class.getDeclaredMethod("entity",ServerLevel.class,CreateContraptionKind.class);factory.setAccessible(true);
        var pos=world.getSharedSpawnPos().offset(18,38,18);world.getChunk(pos);world.setBlock(pos,Blocks.CHEST.defaultBlockState(),3);
        int tested=0;
        var kinds=new CreateContraptionKind[]{CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER};
        try{
            for(var sourceKind:kinds)for(var targetKind:kinds)for(String mode:List.of("item","fluid","cancel-item","cancel-fluid","conflict","incomplete","locks","same-train")){
                if(mode.equals("same-train")&&(sourceKind!=CreateContraptionKind.TRAIN||targetKind!=CreateContraptionKind.TRAIN))continue;
                var source=(AbstractContraptionEntity)factory.invoke(null,world,sourceKind);var target=(AbstractContraptionEntity)factory.invoke(null,world,targetKind);
                source.setPos(pos.getX(),pos.getY()+3,pos.getZ());target.setPos(pos.getX()+3,pos.getY()+3,pos.getZ());
                attachCarriage(source);attachCarriage(target);
                if(mode.equals("same-train"))((CarriageContraptionEntity)target).trainId=((CarriageContraptionEntity)source).trainId;
                check(world.addFreshEntity(source)&&world.addFreshEntity(target),"linked fixture entities spawn");
                try{
                    CartStorageAudit.bind(source);CartStorageAudit.bind(target);
                    var sourceItems=source.getContraption().getStorage().getAllItemStorages().get(BlockPos.ZERO);var targetItems=target.getContraption().getStorage().getAllItemStorages().get(BlockPos.ZERO);
                    var sourceTank=source.getContraption().getStorage().getFluids().storages.get(TANK);var targetTank=target.getContraption().getStorage().getFluids().storages.get(TANK);
                    var diamond=new ItemStack(Items.DIAMOND,8);diamond.set(DataComponents.CUSTOM_NAME,Component.literal("linked components"));sourceItems.setStackInSlot(0,diamond);
                    sourceTank.fill(new FluidStack(Fluids.WATER,1200),FluidAction.EXECUTE);
                    boolean fluid=mode.contains("fluid");
                    List<LogEntry> rows;
                    var capture=CartAutomationAudit.captureChanges(world.getBlockEntity(pos));
                    try{
                        if(fluid){var taken=sourceTank.drain(700,FluidAction.EXECUTE);check(targetTank.fill(taken,FluidAction.EXECUTE)==700,"native fluid receipt");}
                        else{var taken=sourceItems.extractItem(0,5,false);check(targetItems.insertItem(2,taken,false).isEmpty(),"native item receipt");}
                        rows=capture.snapshotRows(!mode.equals("incomplete"));
                    }finally{capture.finish(!mode.equals("incomplete"));}
                    rows.forEach(LogEntry::materializeSnapshots);
                    int participants=mode.equals("same-train")?1:2;
                    check(rows.size()==2+participants&&rows.stream().filter(r->r.type==ActionType.CART_TRANSFER).count()==participants,"linked history lost a participant: "+rows.size());
                    for(var participant:List.of(source,target)){
                        check(rows.stream().anyMatch(r->r.type==ActionType.CART_TRANSFER&&CreateContraptionSources.id(participant).equals(CreateContraptionKind.id(r.source))),"peer transfer absent from own history");
                    }
                    var manifest=rows.getLast();var before=NbtSerde.fromSnbt(manifest.beBefore);var after=NbtSerde.fromSnbt(manifest.beAfter);
                    check(after.getList("Cargo",10).size()==2,"full paired snapshot missing");
                    if(mode.equals("incomplete")){reject(()->CreateTransferRollbackPlan.prepare(before,after),"incomplete linked transfer accepted");tested++;continue;}
                    var plan=CreateTransferRollbackPlan.prepare(before,after);check(plan.constructions().size()==participants&&plan.cargo().size()==2,"planner mixed participants");
                    check(CartLogDetails.describe(world,manifest).stream().filter(c->c.getString().startsWith("Участник:")).count()==2,"GUI lacks participant cells");
                    var missing=after.copy();missing.getList("Cargo",10).remove(1);reject(()->CreateTransferRollbackPlan.prepare(before,missing),"missing peer accepted");
                    var unbalanced=after.copy();unbalanced.getList("Cargo",10).getCompound(1).getCompound(fluid?"Fluid":"Item").putInt(fluid?"amount":"count",99);reject(()->CreateTransferRollbackPlan.prepare(before,unbalanced),"unbalanced paired transfer accepted");
                    var partial=new CartAutomationTransferPlan();partial.manifest(before,after);partial.cargo(before.getList("Cargo",10).getCompound(0),after.getList("Cargo",10).getCompound(0));
                    reject(()->partial.finish(CreateContraptionSources.id(source)),"single construction rollback accepted linked exchange");
                    if(mode.equals("conflict"))targetItems.setStackInSlot(2,new ItemStack(Items.EMERALD,5));
                    boolean old=CartAuditContext.restoring(true);CartAuditContext.rollbackActive=true;
                    try{
                        Class<?> type=Class.forName("com.roften.avilixlogger.compat.create.CreateTransferRollbackCoordinator$Job");var ctor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CreateTransferRollbackPlan.class,long.class);ctor.setAccessible(true);
                        Object job=ctor.newInstance(world.getServer().createCommandSourceStack(),plan,1);
                        var step=type.getDeclaredMethod("step",net.minecraft.server.MinecraftServer.class);step.setAccessible(true);
                        var cancel=type.getDeclaredMethod("cancel",String.class);cancel.setAccessible(true);
                        var applied=type.getDeclaredField("applied");applied.setAccessible(true);
                        boolean canceled=false,rejected=false;int calls=0;
                        while(true){
                            boolean done;
                            try{done=(Boolean)step.invoke(job,world.getServer());}
                            catch(InvocationTargetException error){if(!mode.equals("conflict"))throw error;check(applied.getInt(job)==0,"preflight conflict mutated first participant");rejected=true;break;}
                            if(mode.equals("locks")&&!canceled&&applied.getInt(job)==0&&CartAuditContext.LOCKED_ENTITIES.contains(target.getUUID())){
                                CartAuditContext.restoring(false);
                                try{check(targetItems.insertItem(3,new ItemStack(Items.GOLD_INGOT),false).getCount()==1,"reserved peer accepts cargo");check(targetItems.extractItem(2,1,false).isEmpty(),"reserved peer gives cargo");
                                    check(sourceTank.drain(100,FluidAction.EXECUTE).isEmpty(),"reserved peer gives fluid");
                                    var ref=CartStorageAudit.ref(targetItems);check(CartStorageAudit.locked(ref),"train/bearing endpoint not locked");
                                    var block=target.getContraption().getBlocks().get(BlockPos.ZERO);
                                    target.setBlock(BlockPos.ZERO,new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(BlockPos.ZERO,Blocks.EMERALD_BLOCK.defaultBlockState(),null));
                                    check(target.getContraption().getBlocks().get(BlockPos.ZERO)==block,"reserved peer structure changed");
                                    target.disassemble();check(!target.isRemoved(),"reserved peer disassembled");}
                                finally{CartAuditContext.restoring(true);}canceled=true;
                            }
                            if(mode.startsWith("cancel")&&!canceled&&applied.getInt(job)>0){cancel.invoke(job,"native fixture cancellation");canceled=true;}
                            if(done)break;check(++calls<100,"linked Job did not terminate");
                        }
                        if(mode.equals("conflict")){check(rejected&&sourceItems.getStackInSlot(0).getCount()==3,"conflict changed source");}
                        else if(mode.startsWith("cancel")){
                            check(fluid?sourceTank.getFluidInTank(0).getAmount()==500&&targetTank.getFluidInTank(0).getAmount()==700:sourceItems.getStackInSlot(0).getCount()==3&&targetItems.getStackInSlot(2).getCount()==5,"compensation lost one endpoint");
                        }else{
                            check(fluid?sourceTank.getFluidInTank(0).getAmount()==1200&&targetTank.getFluidInTank(0).isEmpty():ItemStack.matches(diamond,sourceItems.getStackInSlot(0))&&targetItems.getStackInSlot(2).isEmpty(),"joint undo lost resources/components");
                        }
                    }finally{CartAuditContext.restoring(old);CartAuditContext.rollbackActive=false;CartAuditContext.LOCKED_ENTITIES.clear();CartRestoreLocks.clear();}
                    tested++;
                }finally{boolean previous=CartAuditContext.restoring(true);try{((CartEntityAccess)source).avilixlogger$discardForRollback();((CartEntityAccess)target).avilixlogger$discardForRollback();}finally{CartAuditContext.restoring(previous);}CartAutomationAudit.clear();}
            }
        }finally{world.setBlock(pos,Blocks.AIR.defaultBlockState(),3);}
        rootLocks(world,factory,pos);tested++;
        System.out.println("CREATE_LINKED_TRANSFER_NATIVE_OK: "+tested+" scenarios; 16 kind pairs, real item/fluid IO, all-participant history, shared plan, commit, cancellation, conflicts, incomplete operations and locks");
    }
    private static void rootLocks(ServerLevel world,Method factory,BlockPos pos)throws Exception{
        var peer=(CarriageContraptionEntity)factory.invoke(null,world,CreateContraptionKind.TRAIN);attachCarriage(peer);
        peer.setPos(pos.getX(),pos.getY()+3,pos.getZ());check(world.addFreshEntity(peer),"other carriage spawn");CartStorageAudit.bind(peer);
        var id=CreateContraptionSources.id(peer);var storage=peer.getContraption().getStorage().getAllItemStorages().get(BlockPos.ZERO);
        var train=new Train(id,UUID.randomUUID(),null,List.of(),List.of(),false,0);
        // Deliberate sentinels fail if either native train tick runs past its reservation guard.
        train.status=null;train.carriages=null;
        boolean previous=CartAuditContext.restoring(false);CartAuditContext.rollbackActive=true;CartRestoreLocks.construction(id);
        try{
            check(!CartAuditContext.LOCKED_ENTITIES.contains(peer.getUUID()),"root lock fixture accidentally reserves entity");
            check(storage.insertItem(0,new ItemStack(Items.DIAMOND),false).getCount()==1,"another carriage accepts reserved train cargo");
            var block=peer.getContraption().getBlocks().get(BlockPos.ZERO);
            peer.setBlock(BlockPos.ZERO,new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(BlockPos.ZERO,Blocks.EMERALD_BLOCK.defaultBlockState(),null));
            check(peer.getContraption().getBlocks().get(BlockPos.ZERO)==block,"another carriage structure changed");
            peer.disassemble();check(!peer.isRemoved(),"another carriage disassembled");
            int tick=peer.tickCount;peer.tick();check(peer.tickCount==tick,"another carriage ticked during transfer");train.earlyTick(world);train.tick(world);
        }finally{CartAuditContext.rollbackActive=false;CartRestoreLocks.clear();CartAuditContext.restoring(true);try{((CartEntityAccess)peer).avilixlogger$discardForRollback();}finally{CartAuditContext.restoring(previous);}}
    }
    private static void attachCarriage(AbstractContraptionEntity entity)throws Exception{
        if(entity instanceof CarriageContraptionEntity train){
            var carriage=new Carriage(new CarriageBogey(AllBlocks.SMALL_BOGEY.get(),false,new CompoundTag()),null,0);
            var field=CarriageContraptionEntity.class.getDeclaredField("carriage");field.setAccessible(true);field.set(train,carriage);
        }
    }
    private static void reject(Runnable action,String message){try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError(message);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}

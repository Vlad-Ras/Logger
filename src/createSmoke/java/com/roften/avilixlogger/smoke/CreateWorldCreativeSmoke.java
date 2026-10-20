package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.tank.CreativeFluidTankBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.util.*;

/** Real world block capabilities, both native IO directions, immutable restore reservations. */
public final class CreateWorldCreativeSmoke {
    private CreateWorldCreativeSmoke(){}
    public static void run(ServerLevel world)throws Exception{
        BlockPos pos=world.getSharedSpawnPos().offset(57,43,57);
        var observer=new CartAutomationAudit();int tested=0;
        try{
            for(boolean fluid:List.of(false,true)){
                world.setBlock(pos,fluid?AllBlocks.CREATIVE_FLUID_TANK.getDefaultState():AllBlocks.CREATIVE_CRATE.getDefaultState(),3);
                BlockEntity block=world.getBlockEntity(pos);
                check(block!=null,"world creative block entity missing");
                if(!fluid)BlockEntityBehaviour.get(block,FilteringBehaviour.TYPE).setFilter(new ItemStack(Items.EMERALD));
                if(fluid)((CreativeFluidTankBlockEntity.CreativeSmartFluidTank)((CreativeFluidTankBlockEntity)block).getTankInventory())
                        .setContainedFluid(new FluidStack(Fluids.WATER,1000));
                Object handler=fluid?world.getCapability(Capabilities.FluidHandler.BLOCK,pos,null):world.getCapability(Capabilities.ItemHandler.BLOCK,pos,null);
                check(handler!=null,"creative capability missing");
                for(boolean inbound:List.of(false,true)){
                    var scope=CartAutomationAudit.captureChanges(block);
                    List<LogEntry> rows;
                    try{
                        observer.exposed(world,pos,null,handler);
                        if(fluid){var tank=(IFluidHandler)handler;
                            if(inbound)check(tank.fill(new FluidStack(Fluids.LAVA,17),IFluidHandler.FluidAction.EXECUTE)==17,"creative tank void");
                            else check(tank.drain(17,IFluidHandler.FluidAction.EXECUTE).getAmount()==17,"creative tank supply");
                        }else{var crate=(net.neoforged.neoforge.items.IItemHandler)handler;
                            if(inbound)check(crate.insertItem(1,new ItemStack(Items.DIAMOND,3),false).isEmpty(),"creative crate void");
                            else check(crate.extractItem(0,3,false).getCount()==3,"creative crate supply");
                        }
                        rows=scope.snapshotRows(true);
                    }finally{scope.finish(true);}
                    check(rows.size()==1,"world creative IO not captured exactly once");
                    rows.getFirst().materializeSnapshots();
                    var snapshot=NbtSerde.fromSnbt(rows.getFirst().beAfter);
                    check(snapshot.getString("Kind").equals("world_creative_io")&&!snapshot.getBoolean("Complete"),"standalone creative IO presented as cart undo");
                    var io=snapshot.getCompound("IO");check(io.getString("Direction").equals(inbound?"in":"out")&&io.getString("Kind").equals(fluid?"fluid":"item"),"creative IO direction/type lost");
                    var before=new CompoundTag();var after=new CompoundTag();var entries=new ListTag();entries.add(io.copy());before.put("WorldCreativeIO",entries.copy());after.put("WorldCreativeIO",entries.copy());
                    var plan=new CreateWorldCreativeTransferPlan();plan.add(before,after,(kind,payload)->{},(kind,payload)->{});
                    var type=Class.forName("com.roften.avilixlogger.compat.create.CreateWorldCreativeRestore");
                    var constructor=type.getDeclaredConstructor(net.minecraft.server.MinecraftServer.class,CreateWorldCreativeTransferPlan.IO.class);constructor.setAccessible(true);
                    Object endpoint=constructor.newInstance(world.getServer(),plan.finish().getFirst());
                    boolean previous=CartAuditContext.rollbackActive;
                    try{
                        var verify=type.getMethod("verifyAfter");verify.setAccessible(true);verify.invoke(endpoint);
                        CartAuditContext.rollbackActive=true;
                        if(fluid){var tank=(IFluidHandler)handler;check(tank.drain(1,IFluidHandler.FluidAction.EXECUTE).isEmpty()&&tank.fill(new FluidStack(Fluids.WATER,1),IFluidHandler.FluidAction.EXECUTE)==0,"creative fluid lock failed");}
                        else{var crate=(net.neoforged.neoforge.items.IItemHandler)handler;check(crate.extractItem(0,1,false).isEmpty()&&crate.insertItem(1,new ItemStack(Items.STONE),false).getCount()==1,"creative item lock failed");}
                    }finally{CartAuditContext.rollbackActive=previous;CartRestoreLocks.clear();}
                    tested++;
                }
            }
        }finally{world.setBlock(pos,Blocks.AIR.defaultBlockState(),3);CartRestoreLocks.clear();}
        System.out.println("CREATE_WORLD_CREATIVE_NATIVE_OK: "+tested+" native item/fluid supply and void operations, endpoint validation and locks");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

package com.roften.avilixlogger.smoke;

import com.mojang.authlib.GameProfile;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.core.Direction;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.util.*;

/** Actual transformed lava/water collisions with two distinct nearby players. */
@EventBusSubscriber(modid="avilixlogger")
public final class FluidAttributionSmoke {
    public record Observation(UUID actor,String source){}
    private static BlockPos watchedCobble,watchedStone,watchedWater,watchedSupport,watchedFlower;
    private static Observation cobble,stone,water,support,flower;
    private static final List<String> trace=new ArrayList<>();
    public static void observe(BlockPos pos,net.minecraft.world.level.block.state.BlockState state){
        if(pos==null)return;
        boolean c=pos.equals(watchedCobble)&&state.is(Blocks.COBBLESTONE);
        boolean s=pos.equals(watchedStone)&&state.is(Blocks.STONE);
        boolean w=pos.equals(watchedWater)&&state.is(Blocks.WATER);
        boolean d=pos.equals(watchedSupport)&&state.isAir();
        boolean f=pos.equals(watchedFlower)&&state.isAir();
        if(!c&&!s&&!w&&!d&&!f)return;
        var cause=CauseContext.peek();var sample=new Observation(cause==null?null:cause.actorUuid(),MutationSourceResolver.resolveExternalSource());
        trace.add((c?"cobble":s?"stone":w?"water":d?"support":"flower")+"="+sample);
        if(c)cobble=sample;if(s)stone=sample;if(w)water=sample;if(d)support=sample;if(f)flower=sample;
    }
    @SubscribeEvent public static void started(ServerStartedEvent event){
        if(!Boolean.getBoolean("avilixlogger.fluidAttributionSmoke"))return;
        try{run(event.getServer().overworld());System.out.println("FLUID_ATTRIBUTION_NATIVE_OK");}
        catch(Throwable failure){failure.printStackTrace();System.out.println("FLUID_ATTRIBUTION_NATIVE_FAILED");}
        finally{event.getServer().halt(false);}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void run(ServerLevel world)throws Exception{
        var base=world.getSharedSpawnPos().offset(64,50,64);world.getChunkAt(base);
        var initiator=new ServerPlayer(world.getServer(),world,new GameProfile(UUID.randomUUID(),"LavaBuilder"),ClientInformation.createDefault());
        var unrelated=new ServerPlayer(world.getServer(),world,new GameProfile(UUID.randomUUID(),"OtherPlayer"),ClientInformation.createDefault());
        try{
            for(int x=0;x<9;x++)world.setBlock(base.east(x),Blocks.AIR.defaultBlockState(),3);
            watchedCobble=base;watchedWater=base.east();watchedStone=base.east(5);
            cobble=null;stone=null;water=null;support=null;flower=null;trace.clear();
            // A places water earlier; B's unrelated action happens to cause the neighbor update.
            try(var scope=CauseContext.push(initiator,CauseContext.Kind.USE_BLOCK,base.east(),new ItemStack(Items.WATER_BUCKET))){world.setBlock(base.east(),Blocks.WATER.defaultBlockState(),3);}
            try(var scope=CauseContext.push(unrelated,CauseContext.Kind.USE_BLOCK,base,new ItemStack(Items.LAVA_BUCKET))){
                world.setBlock(base,Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL,3),3);
            }
            check(world.getBlockState(base).is(Blocks.COBBLESTONE),"native liquid collision did not create cobblestone");
            // The second vanilla reaction is falling lava meeting water (stone).
            var stone=base.east(5);world.setBlock(stone,Blocks.WATER.defaultBlockState(),3);
            var spread=net.minecraft.world.level.material.LavaFluid.class.getDeclaredMethod("spreadTo",
                    net.minecraft.world.level.LevelAccessor.class,BlockPos.class,net.minecraft.world.level.block.state.BlockState.class,
                    Direction.class,net.minecraft.world.level.material.FluidState.class);
            spread.setAccessible(true);
            try(var scope=CauseContext.push(unrelated,CauseContext.Kind.USE_BLOCK,stone,new ItemStack(Items.STICK))){
                spread.invoke(Fluids.LAVA,world,stone,world.getBlockState(stone),Direction.DOWN,Fluids.LAVA.defaultFluidState());
            }
            check(world.getBlockState(stone).is(Blocks.STONE),"native falling lava collision did not create stone");
            System.out.println("FLUID_ATTRIBUTION_OBSERVED "+trace);
            check(cobble!=null&&FluidAttributionSmoke.stone!=null,"test probe did not observe both native reactions");
            check(cobble.actor()==null&&"system:fluid_reaction".equals(cobble.source()),"NeoForge fluid reaction attributed to unrelated player: "+cobble);
            check(FluidAttributionSmoke.stone.actor()==null&&"minecraft:fluid_reaction".equals(FluidAttributionSmoke.stone.source()),
                    "falling lava reaction attributed to unrelated player: "+FluidAttributionSmoke.stone);
            check(water!=null&&initiator.getUUID().equals(water.actor()),"direct bucket placement lost its actor: "+water);
            // A normal neighbor/shape callback is server logic, even while a player removes its support.
            var dirt=base.east(8);var plant=dirt.above();
            world.setBlock(dirt,Blocks.DIRT.defaultBlockState(),3);
            world.setBlock(plant,Blocks.DANDELION.defaultBlockState(),3);
            watchedSupport=dirt;watchedFlower=plant;
            try(var scope=CauseContext.push(unrelated,CauseContext.Kind.BREAK_BLOCK,dirt,ItemStack.EMPTY)){
                world.setBlock(dirt,Blocks.AIR.defaultBlockState(),3);
            }
            check(support!=null&&unrelated.getUUID().equals(support.actor()),"direct support break lost its actor: "+support);
            check(world.getBlockState(plant).isAir()&&flower!=null&&flower.actor()==null,
                    "server neighbor update charged player for plant removal: "+flower+" state="+world.getBlockState(plant));
            check(CauseContext.peek()==null&&MutationSourceResolver.resolveExternalSource()==null,"reaction context leaked");
        }finally{
            watchedCobble=null;watchedStone=null;watchedWater=null;watchedSupport=null;watchedFlower=null;
            for(int x=0;x<9;x++)world.setBlock(base.east(x),Blocks.AIR.defaultBlockState(),3);
            world.setBlock(base.east(8).above(),Blocks.AIR.defaultBlockState(),3);
        }
    }
}

package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import java.lang.reflect.*;
import java.util.*;
import static com.roften.avilixlogger.smoke.ScgunsNativeSmoke.check;

/** Exact supplied mod's public native operations; production AuthCore stays disabled. */
final class BlockPackStateNativeSmoke {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("AvilixLogger/BlockPackStates");
    static void run(ServerLevel level)throws Exception {
        var player=ScgunsNativeSmoke.player(level,"BlockStateActor");player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        var observed=new ArrayList<CauseContext.Cause>();
        player.connection=new ServerGamePacketListenerImpl(level.getServer(),new Connection(PacketFlow.SERVERBOUND),player,CommonListenerCookie.createInitial(player.getGameProfile(),false)){
            @Override public void send(Packet<?> packet){if(packet instanceof ClientboundSystemChatPacket&&CauseContext.peek()!=null)observed.add(CauseContext.peek());}
            @Override public void send(Packet<?> packet,PacketSendListener callback){send(packet);}
        };
        wrench(level,player,observed);aquarium(level,player);decoration(level,player);
        check(CauseContext.peek()==null,"BlockPack native action leaked actor scope");
    }
    @SuppressWarnings("unchecked") private static void wrench(ServerLevel level,ServerPlayer player,List<CauseContext.Cause> observed)throws Exception {
        var type=Class.forName("com.blockfactory.blockpack.item.WrenchItem");
        var item=BuiltInRegistries.ITEM.stream().filter(type::isInstance).findFirst().orElseThrow();
        var field=type.getDeclaredField("SELECTIONS");field.setAccessible(true);var selections=(Map<UUID,Object>)field.get(null);
        var select=type.getMethod("selectOrCycleProperty",net.minecraft.world.entity.player.Player.class,Level.class,BlockPos.class);
        var editable=type.getDeclaredMethod("editableProps",BlockState.class);editable.setAccessible(true);
        var selection=Class.forName("com.blockfactory.blockpack.item.WrenchItem$Selection");
        var constructor=selection.getDeclaredConstructors()[0];constructor.setAccessible(true);
        var pos=new BlockPos(60,100,0);player.setPos(60.5,100,3.5);var stack=new ItemStack(item);player.setItemInHand(InteractionHand.MAIN_HAND,stack);
        for(var side:Direction.values())level.setBlockAndUpdate(pos.relative(side),Blocks.STONE.defaultBlockState());
        var blocks=new ArrayList<Block>();blocks.add(Blocks.OAK_STAIRS);
        for(String name:List.of("StackableBlock","OpenCloseBlock","NeonSignBlock","ToggleLanternBlock"))blocks.add(block(name));
        int changed=0,properties=0;var kinds=new HashSet<Class<?>>();
        for(var block:blocks){
            level.setBlockAndUpdate(pos,block.defaultBlockState());
            var props=(List<Property<?>>)editable.invoke(null,level.getBlockState(pos));check(!props.isEmpty(),"native wrench fixture has no properties");
            check(props.stream().noneMatch(p->p.equals(BlockStateProperties.WATERLOGGED)),"wrench exposed waterlogged property");
            selections.remove(player.getUUID());var initial=level.getBlockState(pos);
            var event=new PlayerInteractEvent.LeftClickBlock(player,pos,Direction.UP,PlayerInteractEvent.LeftClickBlock.Action.START);
            NeoForge.EVENT_BUS.post(event);check(event.isCanceled()&&selections.containsKey(player.getUUID()),"native left-click did not select/cancel destruction");
            check(initial.equals(level.getBlockState(pos)),"property selection changed the block");
            Object first=selections.get(player.getUUID());select.invoke(null,player,level,pos);check(first==selections.get(player.getUUID()),"native 300ms selection cooldown ignored");
            for(int index=0;index<props.size();index++){
                var property=props.get(index);kinds.add(property.getClass());properties++;
                selections.put(player.getUUID(),constructor.newInstance(level.dimension(),pos,index,System.currentTimeMillis()));
                var start=level.getBlockState(pos);
                for(int value=0;value<property.getPossibleValues().size();value++){
                    var before=level.getBlockState(pos);var expected=before.cycle(property);observed.clear();
                    player.gameMode.useItem(player,level,stack,InteractionHand.MAIN_HAND);
                    var after=level.getBlockState(pos);check(after.equals(expected),"native wrench cycled wrong value: "+property.getName());
                    check(!observed.isEmpty(),"native wrench action did not expose enclosing player cause");var cause=observed.getLast();
                    check(cause.actorUuid().equals(player.getUUID())&&cause.kind()==CauseContext.Kind.USE_ITEM,"wrench lost actual player/use-item scope");
                    var row=BlockStateEvidenceSmoke.changed(level,pos,before,after,cause);
                    check(row.x==pos.getX()&&row.z==pos.getZ()&&row.type==ActionType.BLOCK_INTERACT,"selected target was replaced by player position");
                    check(BlockChangeDetails.changes(row).stream().anyMatch(s->s.contains(property.getName())),"wrench property hidden in details");
                    check(CauseContext.peek()==null,"use-item scope leaked");changed++;
                }
                check(start.equals(level.getBlockState(pos)),"full native property cycle did not return to initial state");
            }
            LOG.info("BLOCKPACK_WRENCH_CASE: {} properties={}",BuiltInRegistries.BLOCK.getKey(block),props.stream().map(Property::getName).toList());
        }
        check(kinds.contains(BooleanProperty.class)&&kinds.contains(IntegerProperty.class)&&kinds.contains(EnumProperty.class),"wrench omitted bool/int/enum coverage");
        level.setBlockAndUpdate(pos,Blocks.OAK_STAIRS.defaultBlockState());var stable=level.getBlockState(pos);
        for(String failure:List.of("missing","expired","foreign","far","no-properties")){
            selections.put(player.getUUID(),constructor.newInstance(failure.equals("foreign")?Level.NETHER:level.dimension(),pos,0,System.currentTimeMillis()-(failure.equals("expired")?10001:0)));
            if(failure.equals("missing"))selections.remove(player.getUUID());
            if(failure.equals("far"))player.setPos(80.5,100,.5);
            if(failure.equals("no-properties"))level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
            var before=level.getBlockState(pos);player.gameMode.useItem(player,level,stack,InteractionHand.MAIN_HAND);
            check(before.equals(level.getBlockState(pos))&&!selections.containsKey(player.getUUID()),"native wrench refusal mutated state/retained selection: "+failure);
            player.setPos(60.5,100,3.5);level.setBlockAndUpdate(pos,stable);
        }
        // The clicked stone is deliberately different from the selected target.
        selections.put(player.getUUID(),constructor.newInstance(level.dimension(),pos,0,System.currentTimeMillis()));var before=level.getBlockState(pos);observed.clear();
        var clicked=pos.above(2);level.setBlockAndUpdate(clicked,Blocks.STONE.defaultBlockState());
        player.gameMode.useItemOn(player,level,stack,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(clicked),Direction.UP,clicked,false));
        check(!before.equals(level.getBlockState(pos))&&!observed.isEmpty()&&observed.getLast().kind()==CauseContext.Kind.USE_BLOCK,"native useOn did not retain selected target/use-block scope");
        var row=BlockStateEvidenceSmoke.changed(level,pos,before,level.getBlockState(pos),observed.getLast());check(row.y!=clicked.getY(),"wrench useOn row followed clicked stone");
        selections.remove(player.getUUID());level.removeBlock(pos,false);level.removeBlock(clicked,false);for(var side:Direction.values())level.removeBlock(pos.relative(side),false);
        LOG.info("BLOCKPACK_WRENCH_NATIVE_OK: {} value changes / {} properties; bool/int/enum, waterlogged exclusion, native selection/cooldown, missing/expired/foreign/far/no-properties refusals, actual useItem/useOn actor and target, journal/details",changed,properties);
    }
    private static void aquarium(ServerLevel level,ServerPlayer player)throws Exception {
        var aquarium=block("AquariumBlock");var pos=new BlockPos(72,100,0);player.setPos(72.5,100,3.5);
        // A previous interrupted stand must not leave a neighbor occupying the placement target.
        for(var direction:Direction.values())level.removeBlock(pos.relative(direction),false);level.removeBlock(pos,false);
        for(var direction:Direction.values()){
            level.setBlockAndUpdate(pos,aquarium.defaultBlockState());var adjacent=pos.relative(direction);
            var before=level.getBlockState(pos);var stack=new ItemStack(aquarium.asItem(),2);player.setItemInHand(InteractionHand.MAIN_HAND,stack);
            var result=player.gameMode.useItemOn(player,level,stack,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),direction,pos,false));
            check(result.consumesAction()&&stack.getCount()==1&&level.getBlockState(adjacent).is(aquarium),"native aquarium placement failed: "+direction+" result="+result+" count="+stack.getCount()+" adjacent="+level.getBlockState(adjacent));
            var after=level.getBlockState(pos);check(!before.equals(after),"aquarium neighbor connection absent");
            var forward=(BooleanProperty)aquarium.getClass().getField(direction.name()).get(null);
            var opposite=(BooleanProperty)aquarium.getClass().getField(direction.getOpposite().name()).get(null);
            check(after.getValue(forward)&&level.getBlockState(adjacent).getValue(opposite),"aquarium connection is not bidirectional");
            BlockStateEvidenceSmoke.changed(level,pos,before,after,null);
            check(player.gameMode.destroyBlock(adjacent)&&!level.getBlockState(pos).getValue(forward),"native aquarium removal left connection");
            BlockStateEvidenceSmoke.changed(level,pos,after,level.getBlockState(pos),null);level.removeBlock(pos,false);
        }
        level.setBlockAndUpdate(pos,aquarium.defaultBlockState());
        check(aquarium instanceof LiquidBlockContainer&&((LiquidBlockContainer)aquarium).placeLiquid(level,pos,level.getBlockState(pos),Fluids.WATER.getSource(false)),"aquarium waterlogging failed");
        var wet=level.getBlockState(pos);check(wet.getValue(BlockStateProperties.WATERLOGGED),"waterlogged property absent");
        check(((BucketPickup)aquarium).pickupBlock(player,level,pos,wet).is(Items.WATER_BUCKET)&&!level.getBlockState(pos).getValue(BlockStateProperties.WATERLOGGED),"native aquarium pickup lost water/property");
        BlockStateEvidenceSmoke.changed(level,pos,wet,level.getBlockState(pos),null);level.removeBlock(pos,false);
        LOG.info("BLOCKPACK_AQUARIUM_NATIVE_OK: six native placements/removals, bidirectional connections, exact consumption, liquid insertion/pickup and detached property evidence");
    }
    private static void decoration(ServerLevel level,ServerPlayer player)throws Exception {
        var pos=new BlockPos(84,100,0);player.setPos(84.5,100,3.5);player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        var lantern=block("ToggleLanternBlock");for(var side:Direction.values())level.setBlockAndUpdate(pos.relative(side),Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos,lantern.defaultBlockState());var be=level.getBlockEntity(pos);var before=level.getBlockState(pos);var saved=NbtSerde.snapshotBlockEntity(level,be);
        player.gameMode.useItemOn(player,level,ItemStack.EMPTY,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
        var after=level.getBlockState(pos);check(!before.equals(after)&&level.getBlockEntity(pos)==be,"native lantern toggle changed BE identity or no state");
        BlockStateEvidenceSmoke.changed(level,pos,before,after,null);
        check(saved.equals(NbtSerde.snapshotBlockEntity(level,be)),"lantern toggle unexpectedly altered persistent animation state");
        var kick=be.getClass().getMethod("kick",long.class);kick.invoke(be,123L);var animation=NbtSerde.snapshotBlockEntity(level,be);
        check(animation.getLong("SwingStart")==123&&be.getClass().getMethod("swingStart").invoke(BlockEntity.loadStatic(pos,after,animation,level.registryAccess())).equals(123L),"native lantern animation NBT round trip failed");
        for(String name:List.of("FlagBlock","WeatherVaneBlock")){
            level.setBlockAndUpdate(pos,block(name).defaultBlockState());var entity=level.getBlockEntity(pos);var tag=NbtSerde.snapshotBlockEntity(level,entity);
            var loaded=BlockEntity.loadStatic(pos,entity.getBlockState(),tag,level.registryAccess());
            check(loaded!=null&&loaded.getType()==entity.getType()&&!(entity instanceof Container),"decorative BE identity/storage mismatch");
            check(((EntityBlock)entity.getBlockState().getBlock()).getTicker(level,entity.getBlockState(),entity.getType())==null,"decorative block gained a server ticker");
            check(tag.equals(NbtSerde.snapshotBlockEntity(level,loadedWithLevel(loaded,level))),"decorative BE native round trip failed");
        }
        level.removeBlock(pos,false);for(var side:Direction.values())level.removeBlock(pos.relative(side),false);
        LOG.info("BLOCKPACK_DECORATION_NATIVE_OK: actual lantern toggle, BE identity, SwingStart native save/load; flag/vane BE round trips and no server ticker; no animation polling added");
    }
    private static BlockEntity loadedWithLevel(BlockEntity be,ServerLevel level){be.setLevel(level);return be;}
    private static Block block(String simpleName){return BuiltInRegistries.BLOCK.stream().filter(b->b.getClass().getName().equals("com.blockfactory.blockpack.block."+simpleName)).sorted(Comparator.comparing(b->BuiltInRegistries.BLOCK.getKey(b).toString())).findFirst().orElseThrow();}
}

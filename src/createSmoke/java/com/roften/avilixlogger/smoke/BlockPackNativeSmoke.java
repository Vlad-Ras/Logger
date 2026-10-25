package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.blockpack.BlockPackGiftAudit;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import static com.roften.avilixlogger.smoke.ScgunsNativeSmoke.check;

/** Only the supplied no-auto-glass-v2 JAR is staged; no production authorization bypass. */
@EventBusSubscriber(modid="avilixlogger")
public final class BlockPackNativeSmoke {
    @SubscribeEvent public static void started(ServerStartedEvent event){
        if(!Boolean.getBoolean("avilixlogger.blockpackSmoke"))return;
        try{run(event.getServer().overworld());status("BLOCKPACK_NATIVE_OK");}
        catch(Throwable failure){org.slf4j.LoggerFactory.getLogger("AvilixLogger/BlockPackSmoke").error("BLOCKPACK_NATIVE_FAILED",failure);}
        finally{event.getServer().halt(false);}
    }
    private static void status(String message){org.slf4j.LoggerFactory.getLogger("AvilixLogger/BlockPackSmoke").info(message);}
    private static void run(ServerLevel level)throws Exception{
        var player=ScgunsNativeSmoke.player(level,"BlockPackActor");player.setPos(.5,100,.5);
        check(!com.roften.avilixlogger.auth.AuthCoreBridge.isAuthorized(level.getServer()),"native fixture must remain unauthorized");
        gifts(level,player);workbench(level,player);fillable(level,player);box(level,player);glass(level,player);preflightFaults();
        check(BlockPackGiftAudit.begin(null)==null,"capture bypassed AuthCore");
    }
    private static void gifts(ServerLevel level,ServerPlayer player)throws Exception{
        var dataType=Class.forName("com.blockfactory.blockpack.event.GiftSavedData");
        var packet=Class.forName("com.blockfactory.blockpack.network.GiftPayloads$SetGiftEnabled");
        var endpoint=Class.forName("com.blockfactory.blockpack.neoforge.BlockPackNeoForge");
        for(var type:List.of(dataType,endpoint))check(Arrays.stream(type.getDeclaredMethods()).anyMatch(m->m.getName().contains("avilixlogger$")),"gift optional hook absent: "+type);
        var data=dataType.getMethod("get",net.minecraft.server.MinecraftServer.class).invoke(null,level.getServer());
        var read=dataType.getMethod("isGiftEnabled");dataType.getMethod("setGiftEnabled",boolean.class).invoke(data,true);
        int[] players={0},queued={0};
        var context=(IPayloadContext)Proxy.newProxyInstance(BlockPackNativeSmoke.class.getClassLoader(),new Class[]{IPayloadContext.class},(p,m,args)->switch(m.getName()){
            case "player" -> {players[0]++;yield player;}
            case "enqueueWork" -> {queued[0]++;((Runnable)args[0]).run();yield CompletableFuture.completedFuture(null);}
            case "toString" -> "BlockPackTestContext";
            default -> throw new AssertionError("unexpected native context call: "+m.getName());
        });
        var receive=endpoint.getDeclaredMethod("lambda$registerPayloads$2",packet,IPayloadContext.class);receive.setAccessible(true);
        check(!player.hasPermissions(2),"gift fixture unexpectedly OP");
        for(boolean requested:List.of(false,false,true)){
            boolean before=(boolean)read.invoke(data);((net.minecraft.world.level.saveddata.SavedData)data).setDirty(false);
            receive.invoke(null,packet.getConstructor(boolean.class).newInstance(requested),context);
            check((boolean)read.invoke(data)==requested&&((net.minecraft.world.level.saveddata.SavedData)data).isDirty()==(before!=requested),"gift native mutation/no-op changed");
            var actor=new BlockPackGiftAudit.Actor(player.getUUID(),player.getGameProfile().getName(),level.dimension().location().toString(),0,100,0);
            var row=BlockPackGiftAudit.capture(actor,before,(boolean)read.invoke(data));check((row!=null)==(before!=requested),"gift evidence confused request with state change");
            if(row!=null){row.materializeSnapshots();check(NbtSerde.fromSnbt(row.beBefore).getBoolean("Value")==before&&NbtSerde.fromSnbt(row.beAfter).getBoolean("Value")==requested,"gift proof lost actual values");}
        }
        check(players[0]==3&&queued[0]==3,"disabled Logger added context reads or native packet execution");
        status("BLOCKPACK_GIFT_NATIVE_OK: actual queued packet, two transitions and one no-op; original executes once, AuthCore disabled; native regular-player request is accepted (upstream permission gap)");
    }
    private static void workbench(ServerLevel level,ServerPlayer player)throws Exception{
        var type=Class.forName("com.blockfactory.blockpack.menu.WorkbenchMenu");var gridField=type.getDeclaredField("craftSlots");gridField.setAccessible(true);
        var pos=new BlockPos(4,100,0);level.setBlockAndUpdate(pos,block("workbench").defaultBlockState());
        for(boolean quick:List.of(false,true)){
            player.getInventory().clearContent();var menu=(AbstractContainerMenu)type.getConstructor(int.class,Inventory.class,ContainerLevelAccess.class).newInstance(61,player.getInventory(),ContainerLevelAccess.create(level,pos));player.containerMenu=menu;
            var grid=(CraftingContainer)gridField.get(menu);grid.setItem(0,new ItemStack(Items.POLISHED_ANDESITE,3));grid.setItem(grid.getWidth(),new ItemStack(Items.POLISHED_ANDESITE,3));
            check(menu.getSlot(0).getItem().is(item("andesite_column"))&&menu.getSlot(0).getItem().getCount()==2,"native BlockPack recipe did not match");
            var before=GenericMenuSnapshot.snapshot(menu,level.registryAccess());var crafts=new ArrayList<ItemStack>();Consumer<PlayerEvent.ItemCraftedEvent> listener=e->{if(e.getEntity()==player)crafts.add(e.getCrafting().copy());};NeoForge.EVENT_BUS.addListener(listener);
            try{if(quick)menu.quickMoveStack(player,0);else{var output=menu.getSlot(0).remove(2);menu.getSlot(0).onTake(player,output);}}
            finally{NeoForge.EVENT_BUS.unregister(listener);}
            check(crafts.size()==1&&crafts.getFirst().is(item("andesite_column"))&&crafts.getFirst().getCount()==2,"native craft event missing/wrong count for quick="+quick+": "+crafts);
            check(grid.getItem(0).getCount()==2&&grid.getItem(grid.getWidth()).getCount()==2,"workbench ingredient consumption changed");
            var after=GenericMenuSnapshot.snapshot(menu,level.registryAccess());check(before!=null&&after!=null&&!before.equals(after),"native crafting slots invisible to generic snapshot");menu.removed(player);
        }
        level.removeBlock(pos,false);status("BLOCKPACK_WORKBENCH_NATIVE_OK: normal/quick-move native recipe, exact crafting event/count, ingredient conservation and detached generic menu snapshots");
    }
    private static void fillable(ServerLevel level,ServerPlayer player)throws Exception{
        var type=Class.forName("com.blockfactory.blockpack.block.FillableBlock");var use=type.getDeclaredMethod("useItemOn",ItemStack.class,net.minecraft.world.level.block.state.BlockState.class,Level.class,BlockPos.class,net.minecraft.world.entity.player.Player.class,InteractionHand.class,BlockHitResult.class);use.setAccessible(true);
        var empty=type.getDeclaredMethod("useWithoutItem",net.minecraft.world.level.block.state.BlockState.class,Level.class,BlockPos.class,net.minecraft.world.entity.player.Player.class,BlockHitResult.class);empty.setAccessible(true);
        var pos=new BlockPos(8,100,0);level.setBlockAndUpdate(pos,block("empty_crate").defaultBlockState());player.getInventory().clearContent();var carrots=new ItemStack(Items.CARROT,21);player.setItemInHand(InteractionHand.MAIN_HAND,carrots);var hit=new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false);
        var initial=level.getBlockState(pos);
        for(int i=0;i<20;i++){var state=level.getBlockState(pos);use.invoke(state.getBlock(),carrots,state,level,pos,player,InteractionHand.MAIN_HAND,hit);}
        var full=level.getBlockState(pos);check(full.getBlock()==block("carrot_crate")&&carrots.getCount()==1&&!full.equals(initial),"fillable block lost 20-item boundary");
        use.invoke(full.getBlock(),carrots,full,level,pos,player,InteractionHand.MAIN_HAND,hit);check(carrots.getCount()==1&&level.getBlockState(pos).equals(full),"full crate consumed extra item");
        player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        for(int i=0;i<20;i++){var state=level.getBlockState(pos);empty.invoke(state.getBlock(),state,level,pos,player,hit);}
        long returned=0;for(var stack:player.getInventory().items)if(stack.is(Items.CARROT))returned+=stack.getCount();
        check(returned==20&&level.getBlockState(pos).getBlock()==block("empty_crate"),"fillable extraction failed conservation");level.removeBlock(pos,false);
        status("BLOCKPACK_FILLABLE_NATIVE_OK: empty->20->full no-op->empty, exact item conservation and immutable block-state transitions");
    }
    private static void box(ServerLevel level,ServerPlayer player)throws Exception{
        var pos=new BlockPos(12,100,0);level.setBlockAndUpdate(pos,block("cardboard_box").defaultBlockState());var be=level.getBlockEntity(pos);check(be instanceof Container,"cardboard inventory absent");var inventory=(Container)be;
        inventory.setItem(0,new ItemStack(Items.DIAMOND,37));inventory.setItem(53,new ItemStack(Items.EMERALD,7));
        var snapshot=ContainerSlotSnapshot.snapshotTag(level,pos);check(snapshot!=null&&snapshot.getInt("Size")==54&&snapshot.getList("Items",10).size()==2,"full cardboard snapshot omitted hidden slots");
        var saved=NbtSerde.snapshotBlockEntity(level,be);check(saved!=null,"cardboard BE snapshot missing");inventory.clearContent();
        check(ContainerSlotSnapshot.apply(level,pos,snapshot)&&inventory.getItem(0).getCount()==37&&inventory.getItem(53).getCount()==7,"cardboard strict slot restore lost normal count/hidden slot");
        var oversized=snapshot.copy();oversized.getList("Items",10).getCompound(0).putInt("count",1024);
        check(!ContainerSlotSnapshot.apply(level,pos,oversized)&&snapshot.equals(ContainerSlotSnapshot.snapshotTag(level,pos)),"invalid oversized cardboard restore was silently clamped or left partial changes");
        var menu=be.getClass().getDeclaredMethod("createMenu",int.class,Inventory.class);menu.setAccessible(true);var actual=(AbstractContainerMenu)menu.invoke(be,62,player.getInventory());
        check(actual.slots.size()==45,"single box should expose 9 external + 36 player slots");
        var restored=BlockEntity.loadStatic(pos,be.getBlockState(),saved,level.registryAccess());check(restored instanceof Container&&((Container)restored).getItem(53).getCount()==7,"native cardboard NBT lost hidden slot");
        actual.removed(player);inventory.clearContent();level.removeBlock(pos,false);
        status("BLOCKPACK_BOX_NATIVE_OK: 9 visible versus 54 stored slots, full detached snapshots, normal count and hidden-slot strict restore/native save-load; oversized request refused and compensated");
    }
    private static void glass(ServerLevel level,ServerPlayer player)throws Exception{
        var method=Class.forName("com.blockfactory.blockpack.event.GlassUpgradeHandler").getMethod("tryUpgrade",Level.class,BlockPos.class,net.minecraft.world.level.block.state.BlockState.class,net.minecraft.world.entity.Entity.class);
        var pos=new BlockPos(16,100,0);int tested=0;
        for(var block:List.of(Blocks.GLASS,Blocks.TINTED_GLASS,Blocks.WHITE_STAINED_GLASS,Blocks.BLACK_STAINED_GLASS)){
            level.setBlockAndUpdate(pos,block.defaultBlockState());check(!(boolean)method.invoke(null,level,pos,level.getBlockState(pos),player)&&level.getBlockState(pos).getBlock()==block,"selected no-auto-glass patch changed glass");tested++;
        }
        level.removeBlock(pos,false);status("BLOCKPACK_NO_AUTO_GLASS_NATIVE_OK: "+tested+" native upgrade attempts return false and preserve vanilla blocks");
    }
    private static void preflightFaults()throws Exception{
        for(String mixin:List.of("GiftStateMixin","GiftPacketMixin")){
            boolean rejected=false;try{new com.roften.avilixlogger.compat.create.mixin.CreateMixinPlugin.Preflight(name->{
                try(var input=Objects.requireNonNull(BlockPackNativeSmoke.class.getClassLoader().getResourceAsStream(name+".class"))){var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(input).accept(node,org.objectweb.asm.ClassReader.SKIP_FRAMES);
                    if(name.equals("com/blockfactory/blockpack/event/GiftSavedData"))node.fields.removeIf(f->f.name.equals("giftEnabled"));
                    if(name.equals("com/blockfactory/blockpack/neoforge/BlockPackNeoForge"))node.methods.removeIf(m->m.name.equals("lambda$registerPayloads$1"));return node;}
            }).check("com/roften/avilixlogger/compat/blockpack/mixin/"+mixin);}catch(Exception expected){rejected=true;}check(rejected,"preflight accepted broken "+mixin);
        }
    }
    private static Block block(String id){var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("bf_blockpack:"+id));check(block!=Blocks.AIR,"missing native block "+id);return block;}
    private static Item item(String id){return block(id).asItem();}
}

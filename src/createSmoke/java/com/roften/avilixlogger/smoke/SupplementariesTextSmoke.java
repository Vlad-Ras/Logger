package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.supplementaries.SupplementariesTextAudit;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.lang.invoke.*;
import java.lang.reflect.Proxy;
import java.util.*;
import static com.roften.avilixlogger.smoke.ScgunsNativeSmoke.check;

/** Supplied JARs only, isolated world, real callbacks and async filtering; no production authorization bypass. */
@EventBusSubscriber(modid="avilixlogger")
public final class SupplementariesTextSmoke {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("AvilixLogger/SupplementariesSmoke");
    @SubscribeEvent public static void started(ServerStartedEvent event){
        if(!Boolean.getBoolean("avilixlogger.supplementariesSmoke"))return;
        try{run(event.getServer().overworld());LOG.info("SUPPLEMENTARIES_NATIVE_OK");}
        catch(Throwable failure){LOG.error("SUPPLEMENTARIES_NATIVE_FAILED",failure);}
        finally{event.getServer().halt(false);}
    }
    private static Object call(Object receiver,String method,Class<?> returns,Class<?>[] parameters,Object...args)throws Throwable{
        // Looking up all TextHolder methods would resolve client-only renderer signatures on a dedicated server.
        var handle=MethodHandles.publicLookup().findVirtual(receiver.getClass(),method,MethodType.methodType(returns,parameters));var all=new ArrayList<Object>();all.add(receiver);all.addAll(Arrays.asList(args));return handle.invokeWithArguments(all);
    }
    private static Object packet(Class<?> type,BlockPos pos,String[][] lines)throws Throwable{return MethodHandles.publicLookup().findConstructor(type,MethodType.methodType(void.class,BlockPos.class,String[][].class)).invoke(pos,lines);}
    private static void callback(Object packet,ServerPlayer player,List<List<FilteredText>> lines)throws Throwable{
        MethodHandles.privateLookupIn(packet.getClass(),MethodHandles.lookup()).findVirtual(packet.getClass(),"updateSignText",MethodType.methodType(void.class,ServerPlayer.class,List.class)).invoke(packet,player,lines);
    }
    private static void user(BlockEntity be,UUID player)throws Throwable{call(be,"setCurrentUser",void.class,new Class[]{UUID.class},player);}
    private static Object holder(BlockEntity be,int index,Class<?> holderType)throws Throwable{return call(be,"getTextHolder",holderType,new Class[]{int.class},index);}
    private static Component[] messages(Object holder,boolean filtered)throws Throwable{return (Component[])call(holder,"getMessages",Component[].class,new Class[]{boolean.class},filtered);}
    private static void run(ServerLevel level)throws Throwable{
        check(!com.roften.avilixlogger.auth.AuthCoreBridge.isAuthorized(level.getServer()),"fixture must remain unauthorized");
        var packetType=Class.forName("net.mehvahdjukaar.supplementaries.common.network.ServerBoundSetTextHolderPacket");var holderType=Class.forName("net.mehvahdjukaar.supplementaries.common.block.TextHolder");
        check(Arrays.stream(packetType.getDeclaredMethods()).anyMatch(m->m.getName().contains("avilixlogger$textPacket")),"text packet mixin absent");
        var blocks=List.of(BuiltInRegistries.BLOCK.get(ResourceLocation.parse("supplementaries:doormat")),BuiltInRegistries.BLOCK.get(ResourceLocation.parse("supplementaries:notice_board")),BuiltInRegistries.BLOCK.stream().filter(b->b.getClass().getName().startsWith("net.mehvahdjukaar.supplementaries.common.block.blocks.SignPost")).findFirst().orElseThrow());
        int tested=0;
        for(var block:blocks){
            check(block!=Blocks.AIR,"native text block absent");var pos=new BlockPos(40+tested*5,100,40);var player=ScgunsNativeSmoke.player(level,"SupplementaryEditor");player.setPos(pos.getX()+2.5,pos.getY()+.5,pos.getZ()+.5);
            level.setBlockAndUpdate(pos,block.defaultBlockState());var be=level.getBlockEntity(pos);check(be!=null,"text block entity absent: "+BuiltInRegistries.BLOCK.getKey(block));
            try{
                int count=(int)call(be,"textHoldersCount",int.class,new Class[]{});check(count>=1&&count<=2,"unexpected provider holder count");
                var lines=new ArrayList<List<FilteredText>>();String[][] wire=new String[count][];
                for(int h=0;h<count;h++){int length=messages(holder(be,h,holderType),false).length;check(length>0&&length<=32,"unexpected native text line count");var group=new ArrayList<FilteredText>();wire[h]=new String[length];for(int i=0;i<length;i++){String raw="Текст 'quoted' "+h+":"+i+" & <plain>";var mask=new net.minecraft.network.chat.FilterMask(raw.length());mask.setFiltered(0);group.add(new FilteredText(raw,mask));wire[h][i]=raw;}lines.add(group);}
                var original=NbtSerde.snapshotBlockEntity(level,be);user(be,player.getUUID());var command=packet(packetType,pos,wire);callback(command,player,lines);
                var after=NbtSerde.snapshotBlockEntity(level,be);check(SupplementariesTextAudit.changed(original,after),"native packet edit bypassed holder detection");
                for(int h=0;h<count;h++){check(messages(holder(be,h,holderType),false)[0].getString().contains("quoted")&&messages(holder(be,h,holderType),true)[0].getString().equals(lines.get(h).getFirst().filteredOrEmpty()),"raw/filtered native text or second holder lost");}
                var row=SupplementariesTextAudit.row(player.getUUID(),player.getGameProfile().getName(),level.dimension().location().toString(),pos,be.getBlockState(),original.copy(),after.copy(),true);check(row!=null&&row.actorUuid.equals(player.getUUID())&&row.x==pos.getX()&&row.x!=player.blockPosition().getX(),"text row used click/player position or wrong editor");row.materializeSnapshots();
                var saved=GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row),LogEntry.class);check(saved.beBefore.equals(row.beBefore)&&saved.beAfter.equals(row.beAfter),"text journal lost full before/after");String details=SupplementariesTextAudit.describe(level,saved).stream().map(Component::getString).collect(java.util.stream.Collectors.joining("\n"));check(details.contains("quoted")&&details.contains("После (фильтр)")&&(count==1||details.contains("Нижний указатель")),"native text details lost raw/filter or lower sign");
                user(be,player.getUUID());callback(command,player,lines);check(!SupplementariesTextAudit.changed(after,NbtSerde.snapshotBlockEntity(level,be)),"repeat packet fabricated text edit");
                var different=List.of(List.of(FilteredText.passThrough("Refused")));
                call(be,"setWaxed",void.class,new Class[]{boolean.class},true);user(be,player.getUUID());callback(command,player,different);check(!SupplementariesTextAudit.changed(after,NbtSerde.snapshotBlockEntity(level,be)),"waxed text accepted");call(be,"setWaxed",void.class,new Class[]{boolean.class},false);
                user(be,UUID.randomUUID());callback(command,player,different);check(!SupplementariesTextAudit.changed(after,NbtSerde.snapshotBlockEntity(level,be)),"foreign editor accepted");
                user(be,player.getUUID());player.setPos(pos.getX()+1000,pos.getY(),pos.getZ());callback(command,player,different);check(!SupplementariesTextAudit.changed(after,NbtSerde.snapshotBlockEntity(level,be)),"far editor accepted");player.setPos(pos.getX()+2.5,pos.getY()+.5,pos.getZ()+.5);
                CartAuditContext.rollbackActive=true;CartRestoreLocks.block(level,pos);user(be,player.getUUID());try{callback(command,player,different);check(!SupplementariesTextAudit.changed(after,NbtSerde.snapshotBlockEntity(level,be)),"reserved provider accepted text while AuthCore disabled");}finally{CartRestoreLocks.clear();CartAuditContext.rollbackActive=false;}
                check(NbtSerde.readBlockEntity(level,pos,original)&&!SupplementariesTextAudit.changed(original,NbtSerde.snapshotBlockEntity(level,be)),"native full BE restore lost original holder text");
                // Exercise the real public packet, common-pool filter and server executor, not just its callback.
                user(be,player.getUUID());var contextType=Class.forName("net.mehvahdjukaar.moonlight.api.platform.network.Message$Context");int[] reads={0};var context=Proxy.newProxyInstance(contextType.getClassLoader(),new Class[]{contextType},(proxy,method,args)->{if(method.getName().equals("getPlayer")){reads[0]++;return player;}return null;});
                MethodHandles.publicLookup().findVirtual(packetType,"handle",MethodType.methodType(void.class,contextType)).invoke(command,context);
                var live=messages(holder(be,0,holderType),false);long deadline=System.nanoTime()+5_000_000_000L;level.getServer().managedBlock(()->live[0].getString().equals(wire[0][0])||System.nanoTime()>=deadline);
                check(reads[0]==1&&messages(holder(be,0,holderType),false)[0].getString().equals(wire[0][0]),"public packet/filter/server handoff failed or read context twice");
                check(SupplementariesTextAudit.begin(player,pos)==null&&!SupplementariesTextAudit.active(),"fixture bypassed AuthCore or retained scope");
                LOG.info("SUPPLEMENTARIES_TEXT_PROVIDER_NATIVE_OK: {} holders={}, actual callback/public async packet, raw/filtered, no-op/wax/editor/distance refusal, reserved target, exact actor/block position, native BE restore and journal/details; production capture unauthorized",BuiltInRegistries.BLOCK.getKey(block),count);tested++;
            }finally{CartRestoreLocks.clear();CartAuditContext.rollbackActive=false;level.removeBlock(pos,false);}
        }
        var missing=new BlockPos(10_000_000,100,10_000_000);check(!level.hasChunkAt(missing),"unloaded test target already loaded");var player=ScgunsNativeSmoke.player(level,"UnloadedTextEditor");callback(packet(packetType,missing,new String[][]{{"unloaded"}}),player,List.of(List.of(FilteredText.passThrough("unloaded"))));check(!level.hasChunkAt(missing),"text packet force-loaded a chunk");
        faults();check(tested==3,"not all native text providers checked");LOG.info("SUPPLEMENTARIES_TEXT_NATIVE_OK: three actual providers, unloaded target refusal, both optional hooks and injected contract faults");
    }
    private static void faults()throws Exception{
        for(String mixin:List.of("TextPacketMixin","TextHolderMixin")){boolean rejected=false;try{new com.roften.avilixlogger.compat.create.mixin.CreateMixinPlugin.Preflight(name->{try(var in=Objects.requireNonNull(SupplementariesTextSmoke.class.getClassLoader().getResourceAsStream(name+".class"))){var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(in).accept(node,org.objectweb.asm.ClassReader.SKIP_FRAMES);if(name.endsWith("ServerBoundSetTextHolderPacket"))node.methods.removeIf(m->m.name.equals("updateSignText"));if(name.endsWith("/TextHolder"))node.methods.removeIf(m->m.name.equals("acceptClientMessages"));return node;}}).check("com/roften/avilixlogger/compat/supplementaries/mixin/"+mixin);}catch(Exception expected){rejected=true;}check(rejected,"preflight accepted broken "+mixin);}
    }
}

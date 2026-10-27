package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.supplementaries.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllDisplaySources;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.phys.AABB;
import java.lang.invoke.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static com.roften.avilixlogger.smoke.ScgunsNativeSmoke.check;
import static com.roften.avilixlogger.smoke.SupplementariesTextSmoke.call;

/** Real native targets/menu + detached history/rollback kernels; never grants AuthCore authorization. */
public final class SupplementariesDisplaySmoke {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("AvilixLogger/SupplementariesDisplaySmoke");
    private static final BlockPos ORIGIN=new BlockPos(60,104,45), TARGET=new BlockPos(64,104,45);
    private static DisplayTarget nativeTarget(String simple)throws Throwable{
        return (DisplayTarget)MethodHandles.publicLookup().findConstructor(Class.forName("net.mehvahdjukaar.supplementaries.integration.create."+simple),MethodType.methodType(void.class)).invoke();
    }
    private static net.minecraft.world.level.block.Block block(String id){return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));}
    private static ItemStack displayed(BlockEntity board)throws Throwable{return (ItemStack)call(board,"getDisplayedItem",ItemStack.class,new Class[]{});}
    private static void display(BlockEntity board,ItemStack item)throws Throwable{call(board,"setDisplayedItem",void.class,new Class[]{ItemStack.class},item);board.setChanged();}
    private static Object holder(BlockEntity be,int index)throws Throwable{return call(be,"getTextHolder",Class.forName("net.mehvahdjukaar.supplementaries.common.block.TextHolder"),new Class[]{int.class},index);}
    private static Component[] messages(Object holder,boolean filtered)throws Throwable{return (Component[])call(holder,"getMessages",Component[].class,new Class[]{boolean.class},filtered);}
    private static List<MutableComponent> lines(String... text){return Arrays.stream(text).map(Component::literal).toList();}
    private static ItemStack written(){
        var item=new ItemStack(Items.WRITTEN_BOOK);
        item.set(DataComponents.WRITTEN_BOOK_CONTENT,new WrittenBookContent(new Filterable<>("Исходная книга",Optional.of("Название фильтр")),"NativeAuthor",2,List.of(new Filterable<Component>(Component.literal("Первая raw"),Optional.of(Component.literal("Первая фильтр"))),Filterable.passThrough(Component.literal("Вторая")),Filterable.passThrough(Component.literal("Третья"))),true));
        item.set(DataComponents.CUSTOM_NAME,Component.literal("Именованная книга"));
        var custom=new CompoundTag();custom.putString("fixture","preserve components");item.set(DataComponents.CUSTOM_DATA,CustomData.of(custom));return item;
    }
    private static ItemStack writable(){
        var item=new ItemStack(Items.WRITABLE_BOOK);item.set(DataComponents.WRITABLE_BOOK_CONTENT,new WritableBookContent(List.of(new Filterable<>("Перо raw",Optional.of("Перо фильтр")),Filterable.passThrough("Сохранённая страница"))));
        item.set(DataComponents.CUSTOM_NAME,Component.literal("Книга с пером"));return item;
    }
    public static void run(ServerLevel level)throws Throwable{
        check(!com.roften.avilixlogger.auth.AuthCoreBridge.isAuthorized(level.getServer()),"native display fixture authorized unexpectedly");
        var linkState=block("create:display_link").defaultBlockState().setValue(DisplayLinkBlock.FACING,Direction.UP);
        level.setBlockAndUpdate(ORIGIN.below(),Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(ORIGIN,linkState);
        var link=(DisplayLinkBlockEntity)level.getBlockEntity(ORIGIN);check(link!=null,"native display link missing");link.target(TARGET);
        var context=new DisplayLinkContext(level,link);var text=nativeTarget("TextHolderDisplayTarget");var book=nativeTarget("NoticeBoardDisplayTarget");
        check(Arrays.stream(text.getClass().getDeclaredMethods()).anyMatch(m->m.getName().contains("avilixlogger$display"))&&Arrays.stream(book.getClass().getDeclaredMethods()).anyMatch(m->m.getName().contains("avilixlogger$display")),"automation mixin absent");
        try{
            for(String id:List.of("supplementaries:doormat","supplementaries:notice_board","supplementaries:way_sign")){
                level.setBlockAndUpdate(TARGET,block(id).defaultBlockState());var be=level.getBlockEntity(TARGET);check(be!=null,"display provider missing "+id);
                var h=holder(be,0);check(h instanceof TextHolderAuditAccess,"native holder comparator absent");
                var access=(TextHolderAuditAccess)h;check(access.avilixlogger$matches(0,List.of()),"empty update became change");
                var before=NbtSerde.snapshotBlockEntity(level,be);var state=be.getBlockState();
                text.acceptText(0,lines("Автоматика raw"),context);
                check(messages(h,false)[0].getString().equals("Автоматика raw")&&messages(h,true)[0].getString().equals("Автоматика raw"),"native display lost raw/filter");
                check(access.avilixlogger$matches(0,lines("Автоматика raw")),"repeat display cannot skip snapshots");
                var after=NbtSerde.snapshotBlockEntity(level,be);check(SupplementariesAutomationAudit.changed(before,after),"automation delta missing");
                var row=record(level,be,state,before,after,true);verifyRow(level,row);
                rollback(level,be,row,before,false);
                text.acceptText(messages(h,false).length+1,lines("out of range"),context);check(!SupplementariesAutomationAudit.changed(before,NbtSerde.snapshotBlockEntity(level,be)),"out of range native target changed text");
                check(access.avilixlogger$matches(messages(h,false).length+1,lines("out of range")),"out of range cannot skip snapshot");
                CartAuditContext.rollbackActive=true;CartRestoreLocks.block(level,TARGET);
                try{text.acceptText(0,lines("reserved"),context);check(!SupplementariesAutomationAudit.changed(before,NbtSerde.snapshotBlockEntity(level,be)),"restore lock failed for display text");}
                finally{CartRestoreLocks.clear();CartAuditContext.rollbackActive=false;}
                if(id.equals("supplementaries:way_sign"))check(messages(holder(be,1),false)[0].getString().isEmpty(),"native display unexpectedly edited lower sign");
                LOG.info("SUPPLEMENTARIES_DISPLAY_TEXT_NATIVE_OK: {} native target, comparator/no-op, loaded position, raw/filter, full journal/history/rollback, reserved refusal",id);
                level.removeBlock(TARGET,false);
            }
            level.setBlockAndUpdate(TARGET,block("supplementaries:notice_board").defaultBlockState());var board=level.getBlockEntity(TARGET);
            for(var original:List.of(written(),writable())){
                display(board,original.copy());var before=NbtSerde.snapshotBlockEntity(level,board);var state=board.getBlockState();
                check(SupplementariesAutomationAudit.bookMayChange(displayed(board),1,lines("Новая страница")),"page change predictor refused");
                book.acceptText(1,lines("Новая страница"),context);var result=displayed(board);var content=result.get(DataComponents.WRITTEN_BOOK_CONTENT);
                check(result.is(Items.WRITTEN_BOOK)&&content!=null&&content.pages().get(1).raw().getString().equals("Новая страница"),"native book target missed page");
                check(content.pages().getFirst().raw().getString().equals(original.is(Items.WRITTEN_BOOK)?"Первая raw":"Перо raw")&&content.pages().getFirst().filtered().isPresent(),"native book lost untouched filtered page");
                if(original.is(Items.WRITTEN_BOOK))check(Objects.equals(original.get(DataComponents.CUSTOM_NAME),result.get(DataComponents.CUSTOM_NAME))&&Objects.equals(original.get(DataComponents.CUSTOM_DATA),result.get(DataComponents.CUSTOM_DATA))&&content.title().equals(original.get(DataComponents.WRITTEN_BOOK_CONTENT).title())&&content.generation()==2,"written book metadata/components changed");
                else check(content.author().equals("Data Gatherer")&&result.get(DataComponents.CUSTOM_NAME)==null,"upstream writable conversion changed; preserve observed semantics");
                var after=NbtSerde.snapshotBlockEntity(level,board);var row=record(level,board,state,before,after,true);verifyRow(level,row);
                String detail=SupplementariesTextAudit.describe(level,row).stream().map(Component::getString).collect(java.util.stream.Collectors.joining("\n"));
                check(detail.contains("Новая страница")&&detail.contains("фильтр")&&detail.contains(original.is(Items.WRITTEN_BOOK)?"NativeAuthor":"Перо raw"),"book detail omitted pages/filter/author: "+detail);
                check(!SupplementariesAutomationAudit.bookMayChange(result,1,lines("Новая страница")),"equal written page forces snapshots");
                book.acceptText(1,lines("Новая страница"),context);check(!SupplementariesAutomationAudit.changed(after,NbtSerde.snapshotBlockEntity(level,board)),"repeat book fabricated delta");
                rollback(level,board,row,before,true);check(ItemStack.isSameItemSameComponents(original,displayed(board)),"Logger rollback lost original item/components");
            }
            display(board,written());book.acceptText(0,lines("Первая raw"),context);check(displayed(board).get(DataComponents.WRITTEN_BOOK_CONTENT).pages().getFirst().filtered().isEmpty(),"native filter reset not applied");
            display(board,written());book.acceptText(49,lines("Последняя","Вне лимита"),context);var pages=displayed(board).get(DataComponents.WRITTEN_BOOK_CONTENT).pages();check(pages.size()==50&&pages.get(49).raw().getString().equals("Последняя"),"native 50 page cap/blank extension changed");
            check(!SupplementariesAutomationAudit.bookMayChange(displayed(board),50,lines("ignored")),"full 50 pages falsely predict out of range update");
            CartAuditContext.rollbackActive=true;CartRestoreLocks.block(level,TARGET);var reserved=NbtSerde.snapshotBlockEntity(level,board);
            try{book.acceptText(0,lines("reserved book"),context);check(reserved.equals(NbtSerde.snapshotBlockEntity(level,board)),"restore lock failed for book target");}
            finally{CartRestoreLocks.clear();CartAuditContext.rollbackActive=false;}
            menu(level,board,book,context);
            // A registered source through the real Create gather/transfer dispatch, not a synthetic source.
            display(board,written());((ChestBlockEntity)level.getBlockEntity(ORIGIN.below())).setItem(0,new ItemStack(Items.OAK_SAPLING,8));
            link.activeSource=AllDisplaySources.COUNT_ITEMS.get();link.targetLine=0;link.updateGatheredData();
            check(link.activeTarget!=null&&displayed(board).get(DataComponents.WRITTEN_BOOK_CONTENT).pages().getFirst().raw().getString().contains("8"),"real Create source/gather/target dispatch failed");
            invalidRollback(level,board);
            vanillaSigns(level);
            faults();
            check(SupplementariesAutomationAudit.enter(0,lines("unauthorized"))==null&&SupplementariesAutomationAudit.current()==null,"automation bypassed AuthCore or leaked frame");
            LOG.info("SUPPLEMENTARIES_DISPLAY_NATIVE_OK: native books/conversion/filter/no-op/50-page cap, real menu/map, registered Create gather pipeline, detached worker preparation + tick kernel rollback, invalid history refusal; positive authenticated emit remains untested");
        }finally{CartRestoreLocks.clear();CartAuditContext.rollbackActive=false;level.removeBlock(TARGET,false);level.removeBlock(ORIGIN,false);level.removeBlock(ORIGIN.below(),false);}
    }
    private static LogEntry record(ServerLevel level,BlockEntity be,net.minecraft.world.level.block.state.BlockState beforeState,CompoundTag before,CompoundTag after,boolean completed){
        var row=SupplementariesAutomationAudit.row(level.dimension().location().toString(),be.getBlockPos(),ORIGIN,beforeState,be.getBlockState(),before.copy(),after.copy(),completed);check(row!=null,"automation row missing");row.materializeSnapshots();return row;
    }
    private static void verifyRow(ServerLevel level,LogEntry row){
        var restored=GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row),LogEntry.class);
        check(restored.actorUuid==null&&restored.actorName.equals("SYSTEM[create:display_link]")&&restored.source.equals(SupplementariesAutomationAudit.SOURCE)&&restored.x==TARGET.getX()&&restored.extra.contains("@60,104,45")&&restored.beBefore.equals(row.beBefore)&&restored.beAfter.equals(row.beAfter),"automation journal actor/target/mechanism/full NBT lost");
    }
    private static void rollback(ServerLevel level,BlockEntity be,LogEntry row,CompoundTag expected,boolean budgeted)throws Throwable{
        var saved=GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row),LogEntry.class);
        var storage=new LogStorage(){
            public void append(LogEntry entry){throw new AssertionError("history fixture append");}
            public List<LogEntry> query(LogQuery q){throw new AssertionError("rollback used forward query");}
            public List<LogEntry> queryReverse(LogQuery q){check(!level.getServer().isSameThread(),"history read on server thread");return List.of(saved);}
            public void shutdown(){}
        };
        var future=new FutureTask<RollbackEngine.PreparedRollback>(()->RollbackEngine.prepareBoxRange(storage,row.dim,TARGET,TARGET,row.ts-1000,row.ts+1000,null,EnumSet.of(row.type)));
        var worker=new Thread(future,"supplementaries-history-prepare");worker.setDaemon(true);worker.start();var prepared=future.get(5,TimeUnit.SECONDS);
        check(prepared.blocks().size()==1&&RollbackEngine.previewPrepared(prepared).applied==1,"history preparation/preview lost operation");
        int drops=level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new AABB(TARGET).inflate(3)).size();
        RollbackReport report;
        if(budgeted)report=tickKernel(level,prepared);else report=RollbackEngine.applyPreparedBlockBatch(level,prepared.blocks());
        check(report.applied==1&&report.skipped==0&&!CartAuditContext.restoring(),"rollback failed or restoring scope leaked "+report.skippedReasons);
        check(expected.equals(NbtSerde.snapshotBlockEntity(level,level.getBlockEntity(TARGET))),"Logger rollback did not restore full native BE: expected="+expected+" actual="+NbtSerde.snapshotBlockEntity(level,level.getBlockEntity(TARGET)));
        check(drops==level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new AABB(TARGET).inflate(3)).size(),"rollback duplicated display item drops");
        check(row.beBefore.equals(saved.beBefore)&&row.beAfter.equals(saved.beAfter),"rollback mutated retained history");
        boolean old=CartAuditContext.restoring(true);try{RollbackEngine.applyPreparedBlockBatch(level,prepared.blocks());check(CartAuditContext.restoring(),"nested restore state lost");}finally{CartAuditContext.restoring(old);}
    }
    private static RollbackReport tickKernel(ServerLevel level,RollbackEngine.PreparedRollback prepared)throws Throwable{
        var owner=UUID.randomUUID();var plan=RollbackPlanManager.save(owner,0,System.currentTimeMillis()+1000,null,EnumSet.of(ActionType.BLOCK_ENTITY_NBT_CHANGE),List.of(new RollbackPlanManager.Scope(level.dimension().location().toString(),TARGET,TARGET)));
        var denied=new ArrayList<String>();RollbackCoordinator.startRollback(level.getServer(),owner,plan,()->{throw new AssertionError("unauthorized start");},r->{throw new AssertionError("unauthorized completion");},denied::add);
        check(denied.size()==1&&!RollbackCoordinator.hasActiveRollback(),"public rollback authorization weakened");
        var scopeType=Class.forName(RollbackCoordinator.class.getName()+"$PreparedScope");var ctor=scopeType.getDeclaredConstructor(String.class,RollbackEngine.PreparedRollback.class);ctor.setAccessible(true);
        var jobType=Class.forName(RollbackCoordinator.class.getName()+"$ActiveJob");var jobCtor=jobType.getDeclaredConstructor(UUID.class,RollbackPlanManager.Plan.class,List.class,Consumer.class,Consumer.class);jobCtor.setAccessible(true);
        var result=new AtomicReference<RollbackReport>();var errors=new ArrayList<String>();var job=jobCtor.newInstance(owner,plan,List.of(ctor.newInstance(level.dimension().location().toString(),prepared)),(Consumer<RollbackReport>)result::set,(Consumer<String>)errors::add);
        var tick=jobType.getDeclaredMethod("tick",net.minecraft.server.MinecraftServer.class);tick.setAccessible(true);
        try{for(int i=0;i<20&&result.get()==null;i++)tick.invoke(job,level.getServer());check(result.get()!=null&&errors.isEmpty(),"budgeted kernel did not complete");return result.get();}
        finally{RollbackPlanManager.clear(owner);}
    }
    private static void menu(ServerLevel level,BlockEntity board,DisplayTarget book,DisplayLinkContext context)throws Throwable{
        var player=SupplementariesTextSmoke.player(level,"BoardMenuActor");player.setPos(TARGET.getX()+.5,TARGET.getY(),TARGET.getZ()+.5);
        var menuType=Class.forName("net.mehvahdjukaar.supplementaries.common.inventories.NoticeBoardContainerMenu");
        var constructor=MethodHandles.publicLookup().findConstructor(menuType,MethodType.methodType(void.class,int.class,net.minecraft.world.entity.player.Inventory.class,board.getClass()));
        var map=MapItem.create(level,0,0,(byte)1,true,true);var mapId=map.get(DataComponents.MAP_ID);check(mapId!=null&&MapItem.getSavedData(map,level)!=null,"real saved map absent");map.set(DataComponents.CUSTOM_NAME,Component.literal("Исходная карта"));
        for(var item:List.of(written(),writable(),map)){
            display(board,ItemStack.EMPTY);var menu=(AbstractContainerMenu)constructor.invoke(81,player.getInventory(),board);
            try{
                check(TARGET.equals(ContainerSlotSnapshot.menuStoragePos(level,menu,TARGET)),"board menu address changed");
                menu.setCarried(item.copy());menu.clicked(0,0,ClickType.PICKUP,player);check(menu.getCarried().isEmpty()&&ItemStack.isSameItemSameComponents(displayed(board),item),"real board pickup placement lost components");
                var snapshot=ContainerSlotSnapshot.snapshotTag(level,TARGET);check(snapshot!=null,"board shared container snapshot absent");
                var before=NbtSerde.snapshotBlockEntity(level,board);var state=board.getBlockState();
                menu.clicked(0,0,ClickType.QUICK_MOVE,player);check(displayed(board).isEmpty(),"board quick take failed");
                int slot=-1;for(int i=1;i<menu.slots.size();i++)if(ItemStack.isSameItemSameComponents(menu.getSlot(i).getItem(),item)){check(slot==-1,"board take duplicated item");slot=i;}
                check(slot>=1,"board take lost item");menu.clicked(slot,0,ClickType.QUICK_MOVE,player);check(ItemStack.isSameItemSameComponents(displayed(board),item)&&menu.getSlot(slot).getItem().isEmpty(),"board quick put duplicated/lost components");
                if(item.is(Items.FILLED_MAP)){check(!SupplementariesAutomationAudit.bookMayChange(displayed(board),0,lines("ignored map")),"map became writable");book.acceptText(0,lines("ignored map"),context);check(before.equals(NbtSerde.snapshotBlockEntity(level,board)),"book target changed map");}
                display(board,ItemStack.EMPTY);var after=NbtSerde.snapshotBlockEntity(level,board);var row=record(level,board,state,before,after,true);
                rollback(level,board,row,before,true);check(ItemStack.isSameItemSameComponents(item,displayed(board)),"board item rollback lost components");
                check(ContainerSlotSnapshot.apply(level,TARGET,snapshot),"shared slot rollback failed");
                if(item.is(Items.FILLED_MAP))check(displayed(board).get(DataComponents.MAP_ID).equals(mapId)&&MapItem.getSavedData(displayed(board),level)!=null,"map reference/save data lost");
            }finally{menu.setCarried(ItemStack.EMPTY);menu.removed(player);player.getInventory().clearContent();display(board,ItemStack.EMPTY);}
        }
        LOG.info("SUPPLEMENTARIES_BOARD_MENU_NATIVE_OK: real menu pickup/quick take/quick put, written/writable book + persisted map, shared slot snapshots, component conservation and Logger tick-kernel restore");
    }
    private static void vanillaSigns(ServerLevel level)throws Throwable{
        var player=SupplementariesTextSmoke.player(level,"AmendmentsRollbackEditor");
        for(var block:List.of(Blocks.OAK_SIGN,Blocks.OAK_HANGING_SIGN)){
            level.removeBlock(TARGET,false);level.setBlockAndUpdate(TARGET,block.defaultBlockState());var sign=(SignBlockEntity)level.getBlockEntity(TARGET);
            for(boolean front:new boolean[]{true,false}){
                sign.setAllowedPlayerEditor(player.getUUID());var raw=front?"Лицевая raw":"Обратная raw";var mask=new net.minecraft.network.chat.FilterMask(raw.length());mask.setFiltered(0);
                sign.updateSignText(player,front,List.of(new net.minecraft.server.network.FilteredText(raw,mask),net.minecraft.server.network.FilteredText.passThrough("Вторая"),net.minecraft.server.network.FilteredText.passThrough(""),net.minecraft.server.network.FilteredText.passThrough("")));
            }
            var expected=NbtSerde.snapshotBlockEntity(level,sign);sign.setText(sign.getText(true).setMessage(0,Component.literal("changed")),true);sign.setText(sign.getText(false).setMessage(0,Component.literal("changed back")),false);
            var row=new LogEntry();row.id=1;row.ts=System.currentTimeMillis();row.type=ActionType.BLOCK_ENTITY_NBT_CHANGE;row.dim=level.dimension().location().toString();row.x=TARGET.getX();row.y=TARGET.getY();row.z=TARGET.getZ();row.blockBefore=NbtSerde.writeBlockState(sign.getBlockState());row.beBefore=expected.toString();row.beAfter=NbtSerde.snapshotBlockEntity(level,sign).toString();
            rollback(level,sign,row,expected,true);
            check(sign.getText(true).getMessages(false)[0].getString().equals("Лицевая raw")&&!sign.getText(true).getMessages(true)[0].getString().equals("Лицевая raw")&&sign.getText(false).getMessages(false)[0].getString().equals("Обратная raw"),"vanilla/Amendments rollback lost sides/filter");
        }
        LOG.info("SUPPLEMENTARIES_AMENDMENTS_SIGN_ROLLBACK_NATIVE_OK: standing/hanging sign, native front/back filtered edits, detached preparation and real rollback tick kernel");
    }
    private static void faults()throws Exception{
        for(String mixin:List.of("DisplayTextMixin","DisplayBookMixin"))for(String fault:List.of("method","getter")){
            boolean rejected=false;
            try{new com.roften.avilixlogger.compat.create.mixin.CreateMixinPlugin.Preflight(name->{
                try(var in=Objects.requireNonNull(SupplementariesDisplaySmoke.class.getClassLoader().getResourceAsStream(name+".class"))){
                    var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(in).accept(node,org.objectweb.asm.ClassReader.SKIP_FRAMES);
                    if(name.startsWith("net/mehvahdjukaar/supplementaries/integration/create/")){
                        if(fault.equals("method"))node.methods.removeIf(m->m.name.equals("acceptText"));
                        else node.methods.forEach(m->{for(var ins:m.instructions)if(ins instanceof org.objectweb.asm.tree.MethodInsnNode call&&Set.of("getTextHolder","getDisplayedItem").contains(call.name))call.name="missingGetter";});
                    }return node;
                }
            }).check("com/roften/avilixlogger/compat/supplementaries/mixin/"+mixin);}catch(Exception expected){rejected=true;}
            check(rejected,"broken automation contract accepted "+mixin+"/"+fault);
        }
        LOG.info("SUPPLEMENTARIES_DISPLAY_FAULT_PREFLIGHT_OK: missing target methods and native getter call sites rejected for both optional targets");
    }
    private static void invalidRollback(ServerLevel level,BlockEntity board)throws Throwable{
        display(board,written());var before=NbtSerde.snapshotBlockEntity(level,board);var state=NbtSerde.writeBlockState(board.getBlockState());
        var wrong=before.copy();wrong.putString("id","minecraft:chest");
        int drops=level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new AABB(TARGET).inflate(3)).size();
        for(var snapshot:List.of(new RollbackEngine.PreparedBlockSnapshot(TARGET,state,wrong.toString(),null,ActionType.BLOCK_ENTITY_NBT_CHANGE),new RollbackEngine.PreparedBlockSnapshot(TARGET,"{Name:'minecraft:stone'}",before.toString(),null,ActionType.BLOCK_ENTITY_NBT_CHANGE),new RollbackEngine.PreparedBlockSnapshot(TARGET,state,"{",null,ActionType.BLOCK_ENTITY_NBT_CHANGE))){
            var report=RollbackEngine.applyPreparedBlockBatch(level,List.of(snapshot));check(report.applied==0&&report.skipped==1&&before.equals(NbtSerde.snapshotBlockEntity(level,board)),"invalid history mutated native board");
        }
        var badSlots=ContainerSlotSnapshot.snapshotTag(level,TARGET);badSlots.putInt("Size",2);check(ContainerSlotSnapshot.isValid(badSlots),"compensation fixture must pass structural validation");
        var changed=before.copy();var changedLines=new ListTag();changedLines.add(StringTag.valueOf("interrupted restore"));changed.getCompound("TextHolder").put("message",changedLines);
        CartAuditContext.rollbackActive=true;CartRestoreLocks.block(level,TARGET);
        try{var report=RollbackEngine.applyPreparedBlockBatch(level,List.of(new RollbackEngine.PreparedBlockSnapshot(TARGET,state,changed.toString(),badSlots.toString(),ActionType.BLOCK_ENTITY_NBT_CHANGE)));
            check(report.applied==0&&report.skipped==1&&report.skippedReasons.containsKey("container_restore_failed")&&before.equals(NbtSerde.snapshotBlockEntity(level,board)),"failed slot apply did not compensate full board state under lock");}
        finally{CartRestoreLocks.clear();CartAuditContext.rollbackActive=false;}
        var offThread=new FutureTask<>(()->RollbackEngine.applyPreparedBlockBatch(level,List.of(new RollbackEngine.PreparedBlockSnapshot(TARGET,state,before.toString(),null,ActionType.BLOCK_ENTITY_NBT_CHANGE))));new Thread(offThread,"refused-world-restore").start();check(offThread.get(5,TimeUnit.SECONDS).applied==0,"off-thread rollback touched world");
        check(drops==level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new AABB(TARGET).inflate(3)).size()&&!CartAuditContext.restoring(),"invalid history emitted items or leaked restoring");
        LOG.info("SUPPLEMENTARIES_ROLLBACK_REFUSAL_NATIVE_OK: foreign BE type, stone/BE mismatch, malformed NBT, compensation under lock, off-thread mutation; no world changes or extra item drops");
    }
}

package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.net.CartHistoryNetwork;
import com.roften.avilixlogger.core.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.util.concurrent.*;

/** At most one cart rollback. Queries/copies off-thread; preflight, construction and world edits in bounded steps. */
public final class CartRollbackCoordinator {
    private static final Map<UUID, Preview> PREVIEWS = new HashMap<>();
    private static final Map<UUID,Long> PREVIEW_REQUESTS=new HashMap<>();
    private static long previewSequence;
    private static final Set<UUID> LOCKED = CartAuditContext.LOCKED_ENTITIES;
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8), r -> { Thread t=new Thread(r,"avilixlogger-cart-prepare");t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY-1);return t; });
    static { WORKER.allowCoreThreadTimeOut(true); }
    private static Job active;
    private static boolean preparing;
    private static long generation;
    private static Entity preparingEntity;
    private static UUID preparingActor;
    private record Preview(UUID id,long checkpoint,long expires) {}
    private static long lastProgress;
    private CartRollbackCoordinator() {}
    public static boolean busy() { return preparing || active != null || CreateTransferRollbackCoordinator.busy() || CreateStructuralRollbackCoordinator.busy() || CreateCouplingRollbackCoordinator.busy(); }
    public static boolean locked(Entity e) { return LOCKED.contains(e.getUUID()); }
    static void prepareTransferHistory(Runnable task){WORKER.execute(task);}
    public static boolean query(CommandSourceStack source, Runnable task) {
        if (!LoggerConfig.isEnabled()) { CartHistoryNetwork.error(source,"Logger не авторизован"); return false; }
        try { WORKER.execute(task); return true; } catch (RejectedExecutionException e) { CartHistoryNetwork.error(source,"Очередь запросов занята"); return false; }
    }
    public static void preview(CommandSourceStack source, UUID id,long checkpoint) {
        var player=source.getPlayer(); if(player==null)return;
        long request=generation,token=++previewSequence;PREVIEWS.remove(player.getUUID());PREVIEW_REQUESTS.put(player.getUUID(),token);
        CartHistoryNetwork.progress(source,"Подготовка предпросмотра отката действий…");
        boolean queued=query(source,()->{
            try {
                var plan=CartRollbackPlan.prepare(LoggerRuntime.storage(source.getLevel()),id,checkpoint);
                source.getServer().execute(()->{
                    if(!Objects.equals(PREVIEW_REQUESTS.get(player.getUUID()),token))return;
                    PREVIEW_REQUESTS.remove(player.getUUID());
                    if(request!=generation){CartHistoryNetwork.error(source,"Состояние отката изменилось во время подготовки. Запросите предпросмотр заново");return;}
                    if(!LoggerConfig.isEnabled()||source.getServer().getPlayerList().getPlayer(player.getUUID())!=player||!PermissionUtil.has(source,"avilixlogger.command.rollback",2)){CartHistoryNetwork.error(source,"Авторизация или права изменились");return;}
                    PREVIEWS.put(player.getUUID(),new Preview(id,checkpoint,System.currentTimeMillis()+120000));
                    com.roften.avilixlogger.net.CartHistoryNetwork.confirm(source,"ОТКАТ ДЕЙСТВИЙ конструкции "+id+" к entry="+checkpoint+". Будет изменён мир: блоков "+plan.blocks().size()+", слотов игроков "+plan.recipients().size()+", мировых creative-источников "+plan.worldCreative().size()+", внешних слотов/баков "+plan.automation().size()+". Целевая форма: "+plan.target().getString("Form")+". Это не отдельная выдача пропавшей штуковины. Подтверждение действует 2 минуты.","log cart confirm "+id+" "+checkpoint);
                });
            } catch(Exception e){source.getServer().execute(()->{if(Objects.equals(PREVIEW_REQUESTS.get(player.getUUID()),token)){PREVIEW_REQUESTS.remove(player.getUUID());errorNow(source,e);}});}
        });
        if(!queued)PREVIEW_REQUESTS.remove(player.getUUID());
    }
    public static void cancelPreview(CommandSourceStack source){if(source.getPlayer()!=null){PREVIEWS.remove(source.getPlayer().getUUID());PREVIEW_REQUESTS.remove(source.getPlayer().getUUID());}}
    public static boolean running(UUID actor){return preparing&&actor.equals(preparingActor)||active!=null&&active.source.getPlayer()!=null&&actor.equals(active.source.getPlayer().getUUID());}
    public static String status(UUID actor){
        if(preparing&&actor.equals(preparingActor))return "Откат действий: подготовка и проверка журнала.";
        if(active!=null&&active.source.getPlayer()!=null&&actor.equals(active.source.getPlayer().getUUID()))return "Откат действий: "+(active.committed?"завершение после фиксации":active.phase==4?"отмена и восстановление резервных копий":"выполнение")+"; блоков обработано "+active.applied+" из "+active.plan.blocks().size()+".";
        if(PREVIEW_REQUESTS.containsKey(actor))return "Откат действий: готовится предпросмотр.";
        var preview=PREVIEWS.get(actor);if(preview!=null&&preview.expires>=System.currentTimeMillis())return "Откат действий: ожидает подтверждения; ID="+preview.id+", entry="+preview.checkpoint+".";
        return "Откат действий: нет активной операции.";
    }
    public static void confirm(CommandSourceStack source,UUID expectedId,long expectedEntry) {
        var p=source.getPlayer();var preview=p==null?null:PREVIEWS.get(p.getUUID());
        if(preview==null||!preview.id.equals(expectedId)||preview.checkpoint!=expectedEntry){CartHistoryNetwork.error(source,"Предпросмотр заменён. Выберите запись заново");return;}
        confirm(source);
    }
    public static void confirm(CommandSourceStack source) {
        ServerPlayer player=source.getPlayer(); if(player==null)return;
        Preview preview=PREVIEWS.remove(player.getUUID());
        if(preview==null || preview.expires<System.currentTimeMillis()){ CartHistoryNetwork.error(source,"Предпросмотр отсутствует или истёк");return; }
        if(busy() || RollbackCoordinator.hasActiveRollback()){CartHistoryNetwork.error(source,"Уже выполняется откат");return;}
        if(!LoggerConfig.isEnabled()){CartHistoryNetwork.error(source,"Logger не авторизован");return;}
        Entity current;
        try { current=CreateCartAudit.loaded(preview.id); } catch (IllegalStateException e) { errorNow(source,e); return; }
        try { validateSource(current); } catch (IllegalStateException e) { errorNow(source,e); return; }
        if(current!=null){CartRestoreLocks.keepLoaded((ServerLevel)current.level(),current.blockPosition());CreateCartAudit.state(current).locked=true;LOCKED.add(current.getUUID());if(current.getVehicle()!=null)LOCKED.add(current.getVehicle().getUUID());}
        preparing=true;CartAuditContext.rollbackActive=true;
        CartStorageAudit.lockMenus(preview.id,source.getServer());
        preparingEntity=current;preparingActor=player.getUUID();long request=++generation;
        CartHistoryNetwork.progress(source,"Подготовка отката конструкции; ожидаю подтверждения записи журнала…");
        try { WORKER.execute(()->{
            try{
                var storage=LoggerRuntime.storage(source.getLevel());long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                if(!AsyncLogProcessor.awaitIdle(deadline)||!storage.awaitVisible(deadline))throw new IllegalStateException("Журнал ещё не записан в БД; откат не начат");
                CartRollbackPlan plan=CartRollbackPlan.prepare(storage,preview.id,preview.checkpoint);
                StagedCartRestore staged=Set.of("entity","blocks").contains(plan.target().getString("Form")) && plan.target().contains("Cart")?new StagedCartRestore(plan.target()):null;
                var recipients=prepareRecipients(plan,source.getServer().registryAccess());
                var automation=prepareAutomation(plan,source.getServer().registryAccess());
                source.getServer().execute(()->{if(request!=generation)return;preparing=false;preparingEntity=null;preparingActor=null;active=new Job(source,plan,staged,current,recipients,automation);});
            }catch(Exception e){ source.getServer().execute(()->{if(request!=generation)return;preparing=false;preparingEntity=null;preparingActor=null;unlock(current);errorNow(source,e);}); }
        });}catch(RejectedExecutionException e){preparing=false;preparingEntity=null;preparingActor=null;unlock(current);errorNow(source,e);}
    }
    public static void onTick(ServerTickEvent.Post event) {
        Job job=active;if(job==null||!event.hasTime())return;
        if(!LoggerConfig.isEnabled() && !job.committed && job.phase!=4){job.failure="Авторизация Logger отозвана";job.phase=4;job.index=job.applied-1;}
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1);int operations=0;
        boolean previous=CartAuditContext.restoring(true);
        try{
            do { if(job.step(event.getServer())){active=null;unlock(job.current);return;} } while(++operations<16 && System.nanoTime()<deadline);
            if(CartHistoryNetwork.viewing(job.source)&&System.nanoTime()-lastProgress>=TimeUnit.SECONDS.toNanos(1)){lastProgress=System.nanoTime();CartHistoryNetwork.progress(job.source,status(job.source.getPlayer().getUUID()));}
        }catch(Exception e){
            if(job.committed){job.phase=6;CreateCartAudit.failed("committed rollback cleanup",e);}
            else if(job.phase==4){active=null;unlock(job.current);errorNow(job.source,new IllegalStateException("Ошибка восстановления резервной копии: "+e.getMessage()));}
            else {job.failure=e.getMessage();job.phase=4;job.index=job.applied-1;}
        }finally{CartAuditContext.restoring(previous);}
    }
    public static void cancel(CommandSourceStack source) {
        if(preparing && source.getPlayer()!=null && source.getPlayer().getUUID().equals(preparingActor)){generation++;preparing=false;unlock(preparingEntity);preparingEntity=null;preparingActor=null;}
        if(active!=null && !active.committed && active.phase!=4 && Objects.equals(active.source.getEntity(),source.getEntity())) {active.failure="Отменено администратором";active.phase=4;active.index=active.applied-1;}
        cancelPreview(source);
    }
    public static void logout(UUID actor){
        PREVIEWS.remove(actor);PREVIEW_REQUESTS.remove(actor);
        if(preparing&&actor.equals(preparingActor)){generation++;preparing=false;unlock(preparingEntity);preparingEntity=null;preparingActor=null;}
        if(active!=null&&!active.committed&&active.source.getPlayer()!=null&&actor.equals(active.source.getPlayer().getUUID())&&active.phase!=4){active.failure="Администратор вышел";active.phase=4;active.index=active.applied-1;}
    }
    public static void stop() {
        generation++;
        boolean previous=CartAuditContext.restoring(true);
        try {
            // Shutdown occurs before level saving; never persist half of an interrupted undo.
            if(active!=null) {
                if(active.committed){
                    if(active.current!=null && !active.current.isRemoved())((CartEntityAccess)active.current).avilixlogger$discardForRollback();
                    if(active.consumedBase!=null && !active.consumedBase.isRemoved())active.consumedBase.discard();
                    if(active.staged!=null && active.staged.entity!=null)CreateCartAudit.state(active.staged.entity).locked=false;
                    for(Entity drop:active.spawned)if(drop!=null&&!drop.isRemoved())drop.discard();
                }
                else {
                    for(int i=active.worldApplied-1;i>=0;i--)active.worldItems.get(i).compensate();
                    for(int i=active.automationApplied-1;i>=0;i--)active.automation.get(i).compensate();
                    for(int i=active.recipientApplied-1;i>=0;i--)active.recipients.get(i).compensate();
                    for(int i=active.applied-1;i>=0;i--){var b=active.backups.get(i);apply(b.level,b.pos,b.state,b.be,b.slots);}
                }
                unlock(active.current);
            }
        } finally {CartAuditContext.restoring(previous);unlock(preparingEntity);active=null;preparing=false;preparingEntity=null;preparingActor=null;PREVIEWS.clear();PREVIEW_REQUESTS.clear();LOCKED.clear();CreateCartAudit.clear();}
    }
    private static void unlock(Entity e){ CartRestoreLocks.clear();CartAuditContext.rollbackActive=false; if(e!=null&&CreateCartAudit.state(e)!=null)CreateCartAudit.state(e).locked=false;LOCKED.clear(); }
    private static void error(CommandSourceStack s,Exception e){s.getServer().execute(()->errorNow(s,e));}
    private static void errorNow(CommandSourceStack s,Exception e){com.roften.avilixlogger.net.CartHistoryNetwork.error(s,"Откат конструкции: "+e.getMessage());}
    private static ServerLevel level(MinecraftServer server,String dim){var l=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(dim)));if(l==null)throw new IllegalStateException("Измерение недоступно: "+dim);return l;}
    private record Backup(ServerLevel level,BlockPos pos,BlockState state,CompoundTag be,CompoundTag slots){}
    private interface ItemLocation { ItemStack get(); void set(ItemStack value); }
    record AutomationData(CartAutomationTransferPlan.SlotUndo undo,Object before,Object after){}
    private record BlockAddress(String dimension,long pos){}
    private static List<AutomationData> prepareAutomation(CartRollbackPlan plan,net.minecraft.core.HolderLookup.Provider registries){
        List<AutomationData> result=new ArrayList<>();
        Set<BlockAddress> changedBlocks=new HashSet<>();for(var block:plan.blocks())changedBlocks.add(new BlockAddress(block.dimension(),block.pos().asLong()));
        for(var undo:plan.automation()){
            if(changedBlocks.contains(new BlockAddress(undo.address().getString("Dimension"),undo.address().getLong("Pos"))))throw new IllegalStateException("Хранилище одновременно изменено в истории блоков и внешнего обмена; выберите контрольную точку после изменения блока");
            if(undo.address().contains("DoubleChest",10)&&changedBlocks.contains(new BlockAddress(undo.address().getString("Dimension"),undo.address().getCompound("DoubleChest").getLong("Pos"))))throw new IllegalStateException("Вторая половина сундука изменена в истории блоков; выберите контрольную точку после изменения блока");
            boolean item=undo.address().getString("Kind").equals("item");
            result.add(new AutomationData(undo,item?readRecipientItem(undo.before(),registries):readAutomationFluid(undo.before(),registries),item?readRecipientItem(undo.after(),registries):readAutomationFluid(undo.after(),registries)));
        }return List.copyOf(result);
    }
    static net.neoforged.neoforge.fluids.FluidStack readAutomationFluid(CompoundTag tag,net.minecraft.core.HolderLookup.Provider registries){
        if(tag.isEmpty())return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        var stack=net.neoforged.neoforge.fluids.FluidStack.CODEC.parse(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),tag).result().orElseThrow(()->new IllegalStateException("Жидкость внешнего хранилища не прочитана полностью"));
        if(stack.isEmpty())throw new IllegalStateException("Некорректная жидкость внешнего хранилища");return stack;
    }
    static final class AutomationSlot {
        final AutomationData data;final ServerLevel level;final BlockPos pos;final net.minecraft.core.Direction side;
        final net.minecraft.world.level.block.entity.BlockEntity be,partner;final Object handler;final int slot;boolean applied;
        AutomationSlot(MinecraftServer server,AutomationData data){
            this.data=data;var address=data.undo.address();level=level(server,address.getString("Dimension"));pos=BlockPos.of(address.getLong("Pos"));slot=address.getInt("Slot");side=address.getString("Side").equals("none")?null:net.minecraft.core.Direction.byName(address.getString("Side"));
            if(!level.hasChunkAt(pos))throw new IllegalStateException("Чанк внешнего хранилища не загружен: "+pos);
            var partnerPos=address.contains("DoubleChest",10)?BlockPos.of(address.getCompound("DoubleChest").getLong("Pos")):null;
            if(partnerPos!=null&&!level.hasChunkAt(partnerPos))throw new IllegalStateException("Чанк второй половины сундука не загружен: "+partnerPos);
            partner=partnerPos==null?null:level.getBlockEntity(partnerPos);
            if(partnerPos!=null&&partner==null)throw new IllegalStateException("Вторая половина сундука отсутствует: "+partnerPos);
            be=level.getBlockEntity(pos);handler=resolve();
            if(be==null||handler==null)throw new IllegalStateException("Внешнее хранилище отсутствует: "+pos);
            if(partner!=null&&(!(be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity)||!(partner instanceof net.minecraft.world.level.block.entity.ChestBlockEntity)))throw new IllegalStateException("Половины двойного сундука заменены");
            for(Object value:List.of(data.before,data.after))if(value instanceof ItemStack item?item.getCount()>address.getInt("Limit"):((net.neoforged.neoforge.fluids.FluidStack)value).getAmount()>address.getInt("Limit"))throw new IllegalStateException("Снимок превышает ёмкость внешнего хранилища");
            CartRestoreLocks.keepLoaded(level,pos);CartRestoreLocks.block(level,pos);CartRestoreLocks.handler(handler);
            if(partner!=null){CartRestoreLocks.keepLoaded(level,partner.getBlockPos());CartRestoreLocks.block(level,partner.getBlockPos());CartRestoreLocks.container((net.minecraft.world.Container)partner);CartRestoreLocks.container((net.minecraft.world.Container)be);for(var direction:net.minecraft.core.Direction.values())CartRestoreLocks.block(level,partner.getBlockPos().relative(direction));}
            for(var direction:net.minecraft.core.Direction.values())CartRestoreLocks.block(level,pos.relative(direction));
            verify(data.after);
        }
        private Object resolve(){
            var address=data.undo.address();if(!level.hasChunkAt(pos))return null;
            if(address.contains("DoubleChest",10)){
                var other=BlockPos.of(address.getCompound("DoubleChest").getLong("Pos"));
                if(!level.hasChunkAt(other)||!NbtUtils.writeBlockState(level.getBlockState(pos)).equals(address.getCompound("Block"))
                        ||!NbtUtils.writeBlockState(level.getBlockState(other)).equals(address.getCompound("DoubleChest").getCompound("Block")))return null;
            }
            if(address.getBoolean("DirectContainer")){
                var container=CartAutomationStorage.containerAt(level,pos);if(container==null)return null;
                CartRestoreLocks.container(container);return new net.neoforged.neoforge.items.wrapper.InvWrapper(container);
            }
            return address.getString("Kind").equals("item")?level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,pos,side):level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,pos,side);
        }
        void verify(Object expected){
            var address=data.undo.address();Object live=resolve();
            if(!level.hasChunkAt(pos)||be.isRemoved()||level.getBlockEntity(pos)!=be||live==null)throw new IllegalStateException("Адрес внешнего хранилища изменён: "+pos);
            if(partner!=null&&(partner.isRemoved()||level.getBlockEntity(partner.getBlockPos())!=partner))throw new IllegalStateException("Вторая половина сундука изменена: "+partner.getBlockPos());
            var actual=address.getBoolean("DirectContainer")&&live instanceof net.neoforged.neoforge.items.wrapper.InvWrapper wrapper
                    ?CartAutomationStorage.describeContainer(level,pos,address.getString("Side"),slot,wrapper.getInv())
                    :CartAutomationStorage.describe(level,pos,address.getString("Side"),address.getString("Kind"),slot,live);
            if(actual==null||!CartAutomationStorage.sameAddress(address,actual))throw new IllegalStateException("Тип или размеры внешнего хранилища изменены: "+pos);
            // Providers may return a fresh InvWrapper on each query; verify its contents/address, not wrapper identity.
            if(expected instanceof ItemStack item){if(!ItemStack.matches(item,((net.neoforged.neoforge.items.IItemHandler)live).getStackInSlot(slot)))throw new IllegalStateException("Предмет внешнего слота изменён: "+pos+", слот "+slot);}
            else if(!net.neoforged.neoforge.fluids.FluidStack.matches((net.neoforged.neoforge.fluids.FluidStack)expected,((net.neoforged.neoforge.fluids.capability.IFluidHandler)live).getFluidInTank(slot)))throw new IllegalStateException("Жидкость внешнего бака изменена: "+pos);
            CartRestoreLocks.handler(live);
        }
        void set(Object value){
            if(level.getBlockEntity(pos)!=be||be.isRemoved())throw new IllegalStateException("Внешнее хранилище удалено: "+pos);
            if(value instanceof ItemStack item){var h=(net.neoforged.neoforge.items.IItemHandlerModifiable)handler;if(item.getCount()>h.getSlotLimit(slot))throw new IllegalStateException("Предмет превышает ёмкость внешнего слота");h.setStackInSlot(slot,item.copy());}
            else{var fluid=(net.neoforged.neoforge.fluids.FluidStack)value;
                if(data.undo.address().getString("Composite").equals("create:basin_output_input")){
                    var basin=(com.simibubi.create.content.processing.basin.BasinBlockEntity)be;
                    var behaviour=slot<2?((com.roften.avilixlogger.compat.create.mixin.BasinOutputTankAccessor)basin).avilixlogger$outputTank():basin.inputTank;
                    var tank=((com.roften.avilixlogger.compat.create.mixin.TankSegmentAccessor)behaviour.getTanks()[slot%2]).avilixlogger$tank();
                    if(fluid.getAmount()>tank.getCapacity())throw new IllegalStateException("Жидкость превышает ёмкость бака чаши");tank.setFluid(fluid.copy());
                }else{var h=(net.neoforged.neoforge.fluids.capability.templates.FluidTank)handler;if(fluid.getAmount()>h.getCapacity())throw new IllegalStateException("Жидкость превышает ёмкость внешнего бака");h.setFluid(fluid.copy());}
            }
            be.setChanged();if(partner!=null)partner.setChanged();verify(value);
        }
        void compensate(){if(applied){set(data.after);applied=false;}}
    }
    private record RecipientData(CartMenuTransferPlan.SlotUndo undo,ItemStack before,ItemStack after){}
    private static List<RecipientData> prepareRecipients(CartRollbackPlan plan,net.minecraft.core.HolderLookup.Provider registries){
        var result=new ArrayList<RecipientData>();for(var undo:plan.recipients()){
            var before=readRecipientItem(undo.before(),registries);var after=readRecipientItem(undo.after(),registries);
            result.add(new RecipientData(undo,before,after));
        }return List.copyOf(result);
    }
    static ItemStack readRecipientItem(CompoundTag tag,net.minecraft.core.HolderLookup.Provider registries){
        if(tag.isEmpty())return ItemStack.EMPTY;
        // Vanilla parse accepts partial codec results. A rollback must preserve every component.
        var stack=ItemStack.CODEC.parse(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),tag)
                .result().orElseThrow(()->new IllegalStateException("Предмет получателя не прочитан полностью"));
        if(stack.isEmpty())throw new IllegalStateException("Некорректный предмет получателя");
        return stack;
    }
    private static final class RecipientSlot {
        final ServerPlayer player;final int slot;final net.minecraft.world.inventory.AbstractContainerMenu menu;
        final ItemStack before,after;boolean applied,compensated;
        RecipientSlot(ServerPlayer player,RecipientData data){
            this.player=player;slot=data.undo.slot();menu=player.containerMenu;before=data.before;after=data.after;
        }
        ItemStack get(){return slot==-1?menu.getCarried():player.getInventory().getItem(slot);}
        void set(ItemStack stack){if(slot==-1)menu.setCarried(stack.copy());else player.getInventory().setItem(slot,stack.copy());player.getInventory().setChanged();menu.broadcastChanges();}
        void verify(MinecraftServer server,ItemStack expected){
            if(server.getPlayerList().getPlayer(player.getUUID())!=player || player.containerMenu!=menu || !ItemStack.matches(expected,get()))throw new IllegalStateException("Инвентарь получателя изменён или игрок вышел: "+player.getScoreboardName()+", слот "+slot);
        }
        void compensate(){if(!compensated){set(after);compensated=true;}}
    }
    private static final class Job {
        final CommandSourceStack source;final CartRollbackPlan plan;final StagedCartRestore staged;final Entity current;
        final ArrayList<Backup> backups=new ArrayList<>();
        final List<Entity> spawned=new ArrayList<>();int spawnIndex;
        final List<RecipientSlot> recipients=new ArrayList<>();final Set<UUID> reservedPlayers=new HashSet<>();
        final Map<ServerPlayer,List<RecipientSlot>> recipientsByPlayer=new IdentityHashMap<>();
        final List<RecipientData> recipientData;
        final List<AutomationData> automationData;final List<AutomationSlot> automation=new ArrayList<>();
        int automationIndex,automationApplied,automationVerify;
        final List<CreateWorldItemRestore> worldItems=new ArrayList<>();final Map<UUID,CreateWorldItemRestore> worldItemById=new HashMap<>();int worldIndex,worldApplied,worldVerify;
        final List<CreateWorldCreativeRestore> worldCreative=new ArrayList<>();int creativeIndex,creativeVerify,creativeCommitVerify;
        int recipientIndex,recipientApplied,recipientVerify;
        Entity standaloneBase,consumedBase;
        final List<ServerPlayer> players;int playerIndex,slotIndex,locationIndex,containerSlot;ItemLocation item;ItemStack expectedItem;
        int phase,index,applied;String failure;boolean committed;
        Job(CommandSourceStack source,CartRollbackPlan plan,StagedCartRestore staged,Entity current){this(source,plan,staged,current,prepareRecipients(plan,source.getServer().registryAccess()));}
        Job(CommandSourceStack source,CartRollbackPlan plan,StagedCartRestore staged,Entity current,List<RecipientData> recipientData){this(source,plan,staged,current,recipientData,prepareAutomation(plan,source.getServer().registryAccess()));}
        Job(CommandSourceStack source,CartRollbackPlan plan,StagedCartRestore staged,Entity current,List<RecipientData> recipientData,List<AutomationData> automationData){this.source=source;this.plan=plan;this.staged=staged;this.current=current;this.recipientData=recipientData;this.automationData=automationData;players=List.copyOf(source.getServer().getPlayerList().getPlayers());}
        private void reservePlayer(ServerPlayer player){if(reservedPlayers.add(player.getUUID())){CartRestoreLocks.player(player);LOCKED.add(player.getUUID());CartHistoryNetwork.notice(player.createCommandSourceStack(),"Инвентарь временно защищён на время связанного отката Create.");}}
        boolean step(MinecraftServer server){
            if(phase==0){ // Incremental inventory lookup, never a global entity/chunk scan.
                if(playerIndex<players.size()){
                    var p=players.get(playerIndex);int total=p.getInventory().getContainerSize();
                    net.minecraft.world.Container inv=slotIndex<total?p.getInventory():p.getEnderChestInventory();
                    int slot=slotIndex<total?slotIndex:slotIndex-total;slotIndex++;
                    if(slotIndex>=total+p.getEnderChestInventory().getContainerSize()){slotIndex=0;playerIndex++;}
                    ItemStack stack=inv.getItem(slot);
                    if(plan.cartId().equals(CreateCartAudit.itemId(stack))){if(item!=null||current!=null||stack.getCount()!=1)throw new IllegalStateException("Найдено несколько копий ID; откат остановлен");reservePlayer(p);item=new ItemLocation(){public ItemStack get(){return server.getPlayerList().getPlayer(p.getUUID())==p?inv.getItem(slot):ItemStack.EMPTY;}public void set(ItemStack v){inv.setItem(slot,v);inv.setChanged();p.containerMenu.broadcastChanges();}};expectedItem=stack.copy();}
                    return false;
                }
                if(locationIndex<plan.itemLocations().size()) {
                    var where=plan.itemLocations().get(locationIndex);var l=level(server,where.dimension());
                    if(!l.hasChunkAt(where.pos())){locationIndex++;containerSlot=0;return false;}
                    var handler=l.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,where.pos(),null);
                    if(!(handler instanceof net.neoforged.neoforge.items.IItemHandlerModifiable writable) || containerSlot>=handler.getSlots()){locationIndex++;containerSlot=0;return false;}
                    int slot=containerSlot++;var stack=handler.getStackInSlot(slot);
                    if(plan.cartId().equals(CreateCartAudit.itemId(stack))) {
                        if(item!=null || current!=null || stack.getCount()!=1)throw new IllegalStateException("Несколько копий ID конструкции");
                        if(!supportedItemHandler(writable) || !supportedSetter(writable))throw new IllegalStateException("Хранилище предмета не поддерживает безопасную замену при откате");
                        CartRestoreLocks.keepLoaded(l,where.pos());CartRestoreLocks.block(l,where.pos());reserveHandlers(l,where.pos(),true);
                        var blockEntity=l.getBlockEntity(where.pos());
                        item=new ItemLocation(){public ItemStack get(){return l.hasChunkAt(where.pos())&&l.getBlockEntity(where.pos())==blockEntity?writable.getStackInSlot(slot):ItemStack.EMPTY;}public void set(ItemStack value){writable.setStackInSlot(slot,value);if(blockEntity!=null)blockEntity.setChanged();}};
                        expectedItem=stack.copy();
                    }
                    return false;
                }
                for(var drop:CartItemTracker.loaded(plan.cartId())) {
                    if(item!=null || current!=null || drop.getItem().getCount()!=1)throw new IllegalStateException("Несколько копий ID конструкции");
                    item=new ItemLocation(){public ItemStack get(){return drop.isRemoved()?ItemStack.EMPTY:drop.getItem();}public void set(ItemStack value){if(value.isEmpty())drop.discard();else drop.setItem(value);}};
                    expectedItem=drop.getItem().copy();LOCKED.add(drop.getUUID());
                }
                String latest=plan.latestForm().getString("Form");
                if(latest.equals("blocks")) {
                    var base=plan.latestForm().getCompound("Cart");
                    if(!base.hasUUID("UUID"))throw new IllegalStateException("В истории нет полного снимка оставшейся вагонетки");
                    standaloneBase=level(server,plan.latestForm().getString("Dimension")).getEntity(base.getUUID("UUID"));
                    if(!(standaloneBase instanceof AbstractMinecart mc) || !mc.getPassengers().isEmpty() || !CreateCartAudit.baseSnapshot(mc).equals(base))throw new IllegalStateException("Оставшаяся вагонетка отсутствует или изменилась");
                    CartRestoreLocks.keepLoaded((ServerLevel)mc.level(),mc.blockPosition());LOCKED.add(mc.getUUID());
                }
                if(current==null&&item==null&&!latest.equals("removed")&&!latest.equals("blocks"))throw new IllegalStateException("Конструкция не найдена среди загруженных сущностей/инвентарей. Загрузите её чанк или верните предмет из хранилища; копия не создана");
                validateSource(current);
                if(current!=null&&current.isRemoved())throw new IllegalStateException("Конструкция изменилась во время подготовки");
                if(staged!=null){var dim=level(server,plan.target().getString("Dimension"));var n=plan.target().getCompound(plan.target().getString("Form").equals("entity")?"Entity":"Cart").getList("Pos",6);if(!dim.hasChunkAt(BlockPos.containing(n.getDouble(0),n.getDouble(1),n.getDouble(2))))throw new IllegalStateException("Целевой чанк не загружен");CartRestoreLocks.keepLoaded(dim,BlockPos.containing(n.getDouble(0),n.getDouble(1),n.getDouble(2)));}
                if(plan.target().getString("Form").equals("item") && (!plan.target().hasUUID("Holder") || server.getPlayerList().getPlayer(plan.target().getUUID("Holder"))==null))throw new IllegalStateException("Владелец целевого предмета должен быть онлайн");
                if(plan.target().getString("Form").equals("item"))reservePlayer(server.getPlayerList().getPlayer(plan.target().getUUID("Holder")));
                phase=1;return false;
            }
            if(phase==1){
                if(index<plan.blocks().size()){
                    var b=plan.blocks().get(index++);var l=level(server,b.dimension());
                    if(!l.hasChunkAt(b.pos()))throw new IllegalStateException("Чанк блока не загружен: "+b.pos());
                    CartRestoreLocks.keepLoaded(l,b.pos());
                    CartRestoreLocks.block(l,b.pos());
                    for(var direction:net.minecraft.core.Direction.values())CartRestoreLocks.block(l,b.pos().relative(direction));
                    reserveHandlers(l,b.pos(),true);
                    verifyBlock(l,b);
                    return false;
                }
                if(spawnIndex<plan.spawned().size()) {
                    var undo=plan.spawned().get(spawnIndex++);var l=level(server,undo.dimension());var entity=l.getEntity(undo.id());
                    if(undo.type().equals("minecraft:item")&&undo.item().isEmpty()){
                        if(entity!=null)throw new IllegalStateException("Полностью подобранный дроп снова присутствует в мире: "+undo.id());
                        spawned.add(null);return false;
                    }
                    if(entity==null || entity.isRemoved())throw new IllegalStateException("Связанный дроп/клей уже убран или выгружен: "+undo.id());
                    if(entity instanceof ItemEntity item) {
                        var expected=NbtSerde.readItemStack(undo.item(),server.registryAccess());
                        if(!ItemStack.matches(expected,item.getItem()))throw new IllegalStateException("Связанный дроп изменился: "+undo.id());
                    }else {
                        var actual=NbtSerde.snapshotEntity(l,entity);
                        if(actual==null || !stableEntity(actual).equals(stableEntity(undo.nbt())))throw new IllegalStateException("Связанный клей изменился: "+undo.id());
                    }
                    LOCKED.add(entity.getUUID());CartRestoreLocks.keepLoaded(l,entity.blockPosition());spawned.add(entity);
                    return false;
                }
                phase=7;index=0;return false;
            }
            if(phase==7){
                if(recipientIndex<plan.recipients().size()){
                    var data=recipientData.get(recipientIndex++);var undo=data.undo;var player=server.getPlayerList().getPlayer(undo.player());
                    if(player==null)throw new IllegalStateException("Получатель должен быть онлайн: "+undo.player());
                    if(undo.slot()< -1 || undo.slot()>=player.getInventory().getContainerSize())throw new IllegalStateException("Слот получателя недоступен");
                    reservePlayer(player);
                    var slot=new RecipientSlot(player,data);slot.verify(server,slot.after);recipients.add(slot);recipientsByPlayer.computeIfAbsent(player,ignored->new ArrayList<>()).add(slot);return false;
                }
                phase=10;return false;
            }
            if(phase==10){
                if(automationIndex<automationData.size()){automation.add(new AutomationSlot(server,automationData.get(automationIndex++)));return false;}
                phase=12;return false;
            }
            if(phase==12){
                if(worldIndex<plan.worldItems().size()){
                    var endpoint=new CreateWorldItemRestore(server,plan.worldItems().get(worldIndex++));
                    worldItems.add(endpoint);worldItemById.put(endpoint.id(),endpoint);return false;
                }
                if(creativeIndex<plan.worldCreative().size()){worldCreative.add(new CreateWorldCreativeRestore(server,plan.worldCreative().get(creativeIndex++)));return false;}
                phase=2;return false;
            }
            if(phase==2){
                if(staged!=null&&!staged.step(level(server,plan.target().getString("Dimension"))))return false;
                phase=3;return false;
            }
            if(phase==3){
                if(index<plan.blocks().size()){
                    var b=plan.blocks().get(index);var l=level(server,b.dimension());
                    verifyBlock(l,b);
                    backups.add(new Backup(l,b.pos(),l.getBlockState(b.pos()),NbtSerde.snapshotBlockEntity(l,l.getBlockEntity(b.pos())),ContainerSlotSnapshot.snapshotTag(l,b.pos())));
                    applied++;index++;
                    apply(l,b.pos(),NbtUtils.readBlockState(l.holderLookup(Registries.BLOCK),b.beforeTag()),b.be(),b.slots());
                    return false;
                }
                phase=8;spawnIndex=0;return false;
            }
            if(phase==8){
                if(recipientApplied<recipients.size()){
                    var slot=recipients.get(recipientApplied);slot.verify(server,slot.after);
                    recipientApplied++;slot.applied=true;slot.set(slot.before);return false;
                }
                phase=11;return false;
            }
            if(phase==11){
                if(automationApplied<automation.size()){var slot=automation.get(automationApplied);slot.verify(slot.data.after);automationApplied++;slot.applied=true;slot.set(slot.data.before);return false;}
                phase=13;return false;
            }
            if(phase==13){
                if(worldApplied<worldItems.size()){worldItems.get(worldApplied++).apply();return false;}
                phase=9;return false;
            }
            if(phase==9){
                if(recipientVerify<recipients.size()){var slot=recipients.get(recipientVerify++);slot.verify(server,slot.before);return false;}
                if(automationVerify<automation.size()){var slot=automation.get(automationVerify++);slot.verify(slot.data.before);return false;}
                if(worldVerify<worldItems.size()){worldItems.get(worldVerify++).verifyBefore();return false;}
                if(creativeVerify<worldCreative.size()){worldCreative.get(creativeVerify++).verifyAfter();return false;}
                phase=5;return false;
            }
            if(phase==4){
                if(worldApplied>0){worldItems.get(--worldApplied).compensate();return false;}
                if(automationApplied>0){automation.get(--automationApplied).compensate();return false;}
                if(recipientApplied>0){recipients.get(--recipientApplied).compensate();return false;}
                if(index>=0 && index<backups.size()){var b=backups.get(index--);apply(b.level,b.pos,b.state,b.be,b.slots);return false;}
                CartHistoryNetwork.error(source,"Откат остановлен: "+failure+". Применённые блоки возвращены из резервных копий.");return true;
            }
            if(phase==5){
                if(creativeCommitVerify<worldCreative.size()){worldCreative.get(creativeCommitVerify++).verifyAfter();return false;}
                if(spawnIndex<spawned.size()){
                    int i=spawnIndex++;Entity entity=spawned.get(i);var undo=plan.spawned().get(i);
                    if(plan.mixedDrops().contains(undo.id())){
                        var restored=worldItemById.get(undo.id());
                        if(restored==null)throw new IllegalStateException("Нет резервной копии смешанного дропа");
                        restored.verifyBefore();return false;
                    }
                    if(entity==null){if(level(server,undo.dimension()).getEntity(undo.id())!=null)throw new IllegalStateException("Подобранный дроп появился перед фиксацией отката");return false;}
                    if(entity.isRemoved() || entity instanceof ItemEntity drop && !ItemStack.matches(NbtSerde.readItemStack(undo.item(),server.registryAccess()),drop.getItem()))throw new IllegalStateException("Связанный дроп изменился перед фиксацией отката");
                    return false;
                }
                commit(server);phase=6;spawnIndex=0;return false;
            }
            if(phase==6){
                if(current!=null && !current.isRemoved()){((CartEntityAccess)current).avilixlogger$discardForRollback();return false;}
                if(consumedBase!=null && !consumedBase.isRemoved()){consumedBase.discard();return false;}
                if(staged!=null && staged.entity!=null && CreateCartAudit.state(staged.entity).locked){CreateCartAudit.attach(staged.entity);CreateCartAudit.state(staged.entity).locked=false;}
                // Large drill output is consumed incrementally under the same tick budget.
                if(spawnIndex<spawned.size()){
                    Entity entity=spawned.get(spawnIndex);var undo=plan.spawned().get(spawnIndex);
                    if(plan.mixedDrops().contains(undo.id()))entity=level(server,undo.dimension()).getEntity(undo.id());
                    if(entity!=null&&!entity.isRemoved())entity.discard();spawnIndex++;return false;
                }
                CartHistoryNetwork.done(source,"Откат конструкции завершён: "+plan.cartId()+", блоков: "+applied);return true;
            }
            throw new IllegalStateException("Неверная фаза отката");
        }
        private void commit(MinecraftServer server){
            if(item!=null&&!ItemStack.matches(expectedItem,item.get()))throw new IllegalStateException("Предмет перемещён во время отката");
            String form=plan.target().getString("Form");ItemStack restored=ItemStack.EMPTY;ServerPlayer holder=null;int targetSlot=-1;
            if(form.equals("item")){
                restored=NbtSerde.readItemStack(plan.target().getCompound("Item"),server.registryAccess());
                if(restored.isEmpty())throw new IllegalStateException("Некорректный предмет в снимке");
                holder=server.getPlayerList().getPlayer(plan.target().getUUID("Holder"));
                if(holder==null)throw new IllegalStateException("Владелец вышел");
                targetSlot=holder.getInventory().getFreeSlot();if(targetSlot<0)throw new IllegalStateException("Нет свободного слота для предмета");
            }
            ServerLevel targetLevel=level(server,plan.target().getString("Dimension"));
            Entity oldCart=current==null?standaloneBase:current.getVehicle();
            consumedBase=oldCart;
            if(standaloneBase instanceof AbstractMinecart mc && (mc.isRemoved() || !CreateCartAudit.baseSnapshot(mc).equals(plan.latestForm().getCompound("Cart"))))throw new IllegalStateException("Вагонетка изменилась во время отката");
            if(current!=null && (current.isRemoved() || CreateCartAudit.loaded(plan.cartId())!=current))throw new IllegalStateException("Исходная конструкция изменилась");
            validateSource(current);
            if(staged!=null){
                // Engine UUIDs may change; the persistent audit ID must not. Register both replacement
                // entities while the original is still intact so spawn cancellation cannot destroy it.
                staged.cart.setUUID(UUID.randomUUID());if(staged.entity!=null)staged.entity.setUUID(UUID.randomUUID());
                LOCKED.add(staged.cart.getUUID());if(staged.entity!=null){LOCKED.add(staged.entity.getUUID());CreateCartAudit.state(staged.entity).locked=true;}
                boolean cartAdded=false,entityAdded=false;
                try {
                    if(!(cartAdded=targetLevel.addFreshEntity(staged.cart)))throw new IllegalStateException("Create не принял восстановленную вагонетку");
                    if(staged.entity==null){entityAdded=true;}else {
                    if(!staged.entity.startRiding(staged.cart,true))throw new IllegalStateException("Create отклонил посадку конструкции");
                    if(!(entityAdded=targetLevel.addFreshEntity(staged.entity)))throw new IllegalStateException("Create не принял восстановленную конструкцию");
                    }
                } finally {
                    if(!entityAdded){if(staged.entity!=null)((CartEntityAccess)staged.entity).avilixlogger$discardForRollback();if(cartAdded)staged.cart.discard();}
                }
            }
            // Finish operations that can fail while the original representation still exists.
            try {
                if(form.equals("item"))holder.getInventory().setItem(targetSlot,restored);
                if(item!=null)item.set(ItemStack.EMPTY);
            }catch(RuntimeException error){
                if(item!=null && !ItemStack.matches(expectedItem,item.get()))try{item.set(expectedItem.copy());}catch(RuntimeException restoreError){error.addSuppressed(restoreError);}
                if(holder!=null && targetSlot>=0)holder.getInventory().setItem(targetSlot,ItemStack.EMPTY);
                if(staged!=null){if(staged.entity!=null)((CartEntityAccess)staged.entity).avilixlogger$discardForRollback();staged.cart.discard();}
                throw error;
            }
            committed=true;
            phase=6;spawnIndex=0;
            CartStorageAudit.closeLockedMenus(server);
            // Once consumed, reporting/cleanup failures must never undo the world a second time.
            if(current!=null)((CartEntityAccess)current).avilixlogger$discardForRollback();
            if(oldCart!=null && (staged!=null || form.equals("item") || form.equals("removed")))oldCart.discard();
            if(staged!=null && staged.entity!=null){CreateCartAudit.attach(staged.entity);CreateCartAudit.state(staged.entity).locked=false;}
            else if(form.equals("item")){
                holder.getInventory().setChanged();holder.containerMenu.broadcastChanges();
            }
            boolean old=CartAuditContext.restoring(false);
            try{LogEntry row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.CART_ROLLBACK;row.dim=targetLevel.dimension().location().toString();row.actorUuid=source.getPlayer()==null?null:source.getPlayer().getUUID();row.actorName=source.getTextName();row.source=CartAuditContext.prefix(plan.cartId())+"rollback:"+plan.checkpointId();row.extra="checkpoint="+plan.checkpointId()+"; blocks="+applied;row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,receipt());LogIdGenerator.ensure(row);LoggerRuntime.storage(targetLevel).append(row);}catch(Exception e){CreateCartAudit.failed("rollback receipt",e);}finally{CartAuditContext.restoring(old);}
        }
        private CompoundTag receipt(){
            // Nested payloads belong to the immutable worker plan; only root UUIDs change.
            CompoundTag tag=shallowCopy(plan.target());
            if(staged!=null){CompoundTag cart=shallowCopy(tag.getCompound("Cart"));cart.putUUID("UUID",staged.cart.getUUID());tag.put("Cart",cart);
                if(staged.entity!=null){CompoundTag entity=shallowCopy(tag.getCompound("Entity"));entity.putUUID("UUID",staged.entity.getUUID());tag.put("Entity",entity);}}
            return tag;
        }
    }
    /** Restore the leaving player's at most 42 cached slots before vanilla saves player data.
     * World compensation and the other recipients continue under the ordinary tick budget. */
    public static void playerLeaving(ServerPlayer player){
        Job job=active;if(job==null || job.committed || !job.reservedPlayers.contains(player.getUUID()))return;
        boolean previous=CartAuditContext.restoring(true);
        try{for(var slot:job.recipientsByPlayer.getOrDefault(player,List.of()))if(slot.applied)slot.compensate();}
        finally{CartAuditContext.restoring(previous);job.failure="Получатель вышел во время отката";job.phase=4;job.index=job.applied-1;}
    }
    public static void invalidated(Entity entity){
        if(CartAuditContext.restoring() || !LOCKED.contains(entity.getUUID()))return;
        if(active!=null && !active.committed && active.phase!=4){active.failure="Связанная сущность изменилась во время отката";active.phase=4;active.index=active.applied-1;}
        else if(preparing){generation++;preparing=false;unlock(preparingEntity);preparingEntity=null;preparingActor=null;}
    }
    private static CompoundTag stableEntity(CompoundTag source){
        CompoundTag tag=source.copy();for(String field:new String[]{"Pos","Motion","Rotation","FallDistance","Fire","Air","OnGround","PortalCooldown","TicksFrozen"})tag.remove(field);return tag;
    }
    private static CompoundTag shallowCopy(CompoundTag source){CompoundTag tag=new CompoundTag();for(String key:source.getAllKeys())tag.put(key,source.get(key));return tag;}
    private static void validateSource(Entity entity){
        if(entity==null)return;
        if(!(entity.getVehicle() instanceof AbstractMinecart base) || base.isRemoved())throw new IllegalStateException("Исходная вагонетка отсутствует или удалена");
        if(!(entity instanceof com.simibubi.create.content.contraptions.OrientedContraptionEntity oriented) || oriented.getCouplingId()!=null || !oriented.getPassengers().isEmpty() || !((com.roften.avilixlogger.compat.create.mixin.CartContraptionAccessor)oriented.getContraption()).avilixlogger$subContraptions().isEmpty())throw new IllegalStateException("Сцепленная или вложенная конструкция требует совместного отката");
        if(entity.getVehicle()!=null && (entity.getVehicle().getPassengers().size()!=1 || entity.getVehicle().getPassengers().getFirst()!=entity))throw new IllegalStateException("На вагонетке есть другие пассажиры");
    }
    static void reserveHandlers(ServerLevel level,BlockPos pos,boolean requireSupported){
        var item=level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,pos,null);
        if(requireSupported && item!=null && !supportedItemHandler(item))throw new IllegalStateException("У инвентаря блока нет безопасной блокировки для отката: "+pos);
        CartRestoreLocks.handler(item);
        var fluid=level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,pos,null);
        if(requireSupported && fluid!=null && !supportedFluidHandler(fluid))throw new IllegalStateException("У жидкости блока нет безопасной блокировки для отката: "+pos);
        CartRestoreLocks.handler(fluid);
    }
    private static boolean supportedFluidHandler(net.neoforged.neoforge.fluids.capability.IFluidHandler handler){
        try{
            Class<?> tank=net.neoforged.neoforge.fluids.capability.templates.FluidTank.class;
            return handler.getClass().getMethod("fill",net.neoforged.neoforge.fluids.FluidStack.class,net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.class).getDeclaringClass()==tank && handler.getClass().getMethod("drain",int.class,net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.class).getDeclaringClass()==tank && handler.getClass().getMethod("drain",net.neoforged.neoforge.fluids.FluidStack.class,net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.class).getDeclaringClass()==tank;
        }catch(ReflectiveOperationException e){return false;}
    }
    private static boolean supportedItemHandler(net.neoforged.neoforge.items.IItemHandler handler){
        try {
            for(var method:new java.lang.reflect.Method[]{handler.getClass().getMethod("insertItem",int.class,ItemStack.class,boolean.class),handler.getClass().getMethod("extractItem",int.class,int.class,boolean.class)}) {
                var owner=method.getDeclaringClass();
                if(owner!=net.neoforged.neoforge.items.ItemStackHandler.class && owner!=net.neoforged.neoforge.items.wrapper.InvWrapper.class && owner!=net.neoforged.neoforge.items.wrapper.CombinedInvWrapper.class && owner!=com.simibubi.create.content.logistics.depot.DepotItemHandler.class)return false;
            }
            return true;
        }catch(ReflectiveOperationException e){return false;}
    }
    private static boolean supportedSetter(net.neoforged.neoforge.items.IItemHandlerModifiable handler){
        try{Class<?> owner=handler.getClass().getMethod("setStackInSlot",int.class,ItemStack.class).getDeclaringClass();return owner==net.neoforged.neoforge.items.ItemStackHandler.class || owner==net.neoforged.neoforge.items.wrapper.InvWrapper.class || owner==net.neoforged.neoforge.items.wrapper.CombinedInvWrapper.class;}catch(ReflectiveOperationException e){return false;}
    }
    private static void verifyBlock(ServerLevel level,CartRollbackPlan.BlockUndo b){
        if(!level.hasChunkAt(b.pos()) || !level.getBlockState(b.pos()).equals(NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK),b.afterTag())))throw new IllegalStateException("Конфликт блока: "+b.pos());
        CompoundTag actualBe=NbtSerde.snapshotBlockEntity(level,level.getBlockEntity(b.pos()));
        CompoundTag actualSlots=ContainerSlotSnapshot.snapshotTag(level,b.pos());
        if(!Objects.equals(actualBe,b.afterBe()) || !Objects.equals(actualSlots,b.afterSlots()))throw new IllegalStateException("NBT/инвентарь блока изменился или не был полностью записан: "+b.pos());
    }
    static void apply(ServerLevel level,BlockPos pos,BlockState state,CompoundTag be,CompoundTag slots){
        level.setBlock(pos,state,net.minecraft.world.level.block.Block.UPDATE_CLIENTS|net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE|net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS|net.minecraft.world.level.block.Block.UPDATE_MOVE_BY_PISTON);
        if(!level.getBlockState(pos).equals(state))throw new IllegalStateException("Блок отклонил восстановление: "+pos);
        if(be!=null){
            var target=level.getBlockEntity(pos);if(target==null)throw new IllegalStateException("NBT блока не восстановлен: "+pos);
            var tag=be.copy();tag.putInt("x",pos.getX());tag.putInt("y",pos.getY());tag.putInt("z",pos.getZ());
            if(target instanceof CreateKineticAccess kinetic)kinetic.avilixlogger$prepareSnapshotLoad();
            target.loadWithComponents(tag,level.registryAccess());target.setChanged();level.sendBlockUpdated(pos,state,state,3);
        }
        if(slots!=null&&!slots.equals(ContainerSlotSnapshot.snapshotTag(level,pos))&&!ContainerSlotSnapshot.apply(level,pos,slots))throw new IllegalStateException("Инвентарь блока не восстановлен: "+pos);
        reserveHandlers(level,pos,true);
    }
}

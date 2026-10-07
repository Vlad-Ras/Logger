package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.LoggerConfig;
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
    private static final Set<UUID> LOCKED = CartAuditContext.LOCKED_ENTITIES;
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8), r -> { Thread t=new Thread(r,"avilixlogger-cart-prepare");t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY-1);return t; });
    private static Job active;
    private static boolean preparing;
    private static long generation;
    private static Entity preparingEntity;
    private static UUID preparingActor;
    private record Preview(UUID id,long checkpoint,long expires) {}
    private CartRollbackCoordinator() {}
    public static boolean busy() { return preparing || active != null; }
    public static boolean locked(Entity e) { return LOCKED.contains(e.getUUID()); }
    public static void query(CommandSourceStack source, Runnable task) {
        if (!LoggerConfig.isEnabled()) { source.sendFailure(Component.literal("Logger не авторизован")); return; }
        try { WORKER.execute(task); } catch (RejectedExecutionException e) { source.sendFailure(Component.literal("Очередь запросов занята")); }
    }
    public static void preview(CommandSourceStack source, UUID id,long checkpoint) {
        var player=source.getPlayer(); if(player==null)return;
        long request=generation;
        query(source,()->{
            try {
                var plan=CartRollbackPlan.prepare(LoggerRuntime.storage(source.getLevel()),id,checkpoint);
                source.getServer().execute(()->{
                    if(request!=generation || !LoggerConfig.isEnabled())return;
                    PREVIEWS.put(player.getUUID(),new Preview(id,checkpoint,System.currentTimeMillis()+120000));
                    source.sendSystemMessage(Component.literal("Конструкция "+id+": возврат к записи "+checkpoint+", блоков мира: "+plan.blocks().size()+", форма: "+plan.target().getString("Form")+". Подтвердить: /log cart confirm (2 минуты)."));
                });
            } catch(Exception e){ error(source,e); }
        });
    }
    public static void confirm(CommandSourceStack source) {
        ServerPlayer player=source.getPlayer(); if(player==null)return;
        Preview preview=PREVIEWS.remove(player.getUUID());
        if(preview==null || preview.expires<System.currentTimeMillis()){ source.sendFailure(Component.literal("Предпросмотр отсутствует или истёк"));return; }
        if(busy() || RollbackCoordinator.hasActiveRollback()){source.sendFailure(Component.literal("Уже выполняется откат"));return;}
        if(!LoggerConfig.isEnabled())return;
        Entity current;
        try { current=CreateCartAudit.loaded(preview.id); } catch (IllegalStateException e) { errorNow(source,e); return; }
        try { validateSource(current); } catch (IllegalStateException e) { errorNow(source,e); return; }
        if(current!=null){CartRestoreLocks.keepLoaded((ServerLevel)current.level(),current.blockPosition());CreateCartAudit.state(current).locked=true;LOCKED.add(current.getUUID());if(current.getVehicle()!=null)LOCKED.add(current.getVehicle().getUUID());}
        preparing=true;CartAuditContext.rollbackActive=true;
        CartStorageAudit.lockMenus(preview.id,source.getServer());
        preparingEntity=current;preparingActor=player.getUUID();long request=++generation;
        source.sendSystemMessage(Component.literal("Подготовка отката конструкции; ожидаю подтверждения записи журнала…"));
        try { WORKER.execute(()->{
            try{
                var storage=LoggerRuntime.storage(source.getLevel());long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                if(!AsyncLogProcessor.awaitIdle(deadline)||!storage.awaitVisible(deadline))throw new IllegalStateException("Журнал ещё не записан в БД; откат не начат");
                CartRollbackPlan plan=CartRollbackPlan.prepare(storage,preview.id,preview.checkpoint);
                StagedCartRestore staged=Set.of("entity","blocks").contains(plan.target().getString("Form")) && plan.target().contains("Cart")?new StagedCartRestore(plan.target()):null;
                source.getServer().execute(()->{if(request!=generation)return;preparing=false;preparingEntity=null;preparingActor=null;active=new Job(source,plan,staged,current);});
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
        }catch(Exception e){
            if(job.committed){job.phase=6;CreateCartAudit.failed("committed rollback cleanup",e);}
            else if(job.phase==4){active=null;unlock(job.current);errorNow(job.source,new IllegalStateException("Ошибка восстановления резервной копии: "+e.getMessage()));}
            else {job.failure=e.getMessage();job.phase=4;job.index=job.applied-1;}
        }finally{CartAuditContext.restoring(previous);}
    }
    public static void cancel(CommandSourceStack source) {
        if(preparing && source.getPlayer()!=null && source.getPlayer().getUUID().equals(preparingActor)){generation++;preparing=false;unlock(preparingEntity);preparingEntity=null;preparingActor=null;}
        if(active!=null && !active.committed && active.phase!=4 && Objects.equals(active.source.getEntity(),source.getEntity())) {active.failure="Отменено администратором";active.phase=4;active.index=active.applied-1;}
        if(source.getPlayer()!=null) PREVIEWS.remove(source.getPlayer().getUUID());
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
                    for(Entity drop:active.spawned)if(!drop.isRemoved())drop.discard();
                }
                else for(int i=active.applied-1;i>=0;i--){var b=active.backups.get(i);apply(b.level,b.pos,b.state,b.be,b.slots);}
                unlock(active.current);
            }
        } finally {CartAuditContext.restoring(previous);unlock(preparingEntity);active=null;preparing=false;preparingEntity=null;preparingActor=null;PREVIEWS.clear();LOCKED.clear();CreateCartAudit.clear();}
    }
    private static void unlock(Entity e){ CartRestoreLocks.clear();CartAuditContext.rollbackActive=false; if(e!=null&&CreateCartAudit.state(e)!=null)CreateCartAudit.state(e).locked=false;LOCKED.clear(); }
    private static void error(CommandSourceStack s,Exception e){s.getServer().execute(()->errorNow(s,e));}
    private static void errorNow(CommandSourceStack s,Exception e){s.sendFailure(Component.literal("Откат конструкции: "+e.getMessage()));}
    private static ServerLevel level(MinecraftServer server,String dim){var l=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(dim)));if(l==null)throw new IllegalStateException("Измерение недоступно: "+dim);return l;}
    private record Backup(ServerLevel level,BlockPos pos,BlockState state,CompoundTag be,CompoundTag slots){}
    private interface ItemLocation { ItemStack get(); void set(ItemStack value); }
    private static final class Job {
        final CommandSourceStack source;final CartRollbackPlan plan;final StagedCartRestore staged;final Entity current;
        final ArrayList<Backup> backups=new ArrayList<>();
        final List<Entity> spawned=new ArrayList<>();int spawnIndex;
        Entity standaloneBase,consumedBase;
        final List<ServerPlayer> players;int playerIndex,slotIndex,locationIndex,containerSlot;ItemLocation item;ItemStack expectedItem;
        int phase,index,applied;String failure;boolean committed;
        Job(CommandSourceStack source,CartRollbackPlan plan,StagedCartRestore staged,Entity current){this.source=source;this.plan=plan;this.staged=staged;this.current=current;players=List.copyOf(source.getServer().getPlayerList().getPlayers());}
        boolean step(MinecraftServer server){
            if(phase==0){ // Incremental inventory lookup, never a global entity/chunk scan.
                if(playerIndex<players.size()){
                    var p=players.get(playerIndex);int total=p.getInventory().getContainerSize();
                    net.minecraft.world.Container inv=slotIndex<total?p.getInventory():p.getEnderChestInventory();
                    int slot=slotIndex<total?slotIndex:slotIndex-total;slotIndex++;
                    if(slotIndex>=total+p.getEnderChestInventory().getContainerSize()){slotIndex=0;playerIndex++;}
                    ItemStack stack=inv.getItem(slot);
                    if(plan.cartId().equals(CreateCartAudit.itemId(stack))){if(item!=null||current!=null||stack.getCount()!=1)throw new IllegalStateException("Найдено несколько копий ID; откат остановлен");item=new ItemLocation(){public ItemStack get(){return inv.getItem(slot);}public void set(ItemStack v){inv.setItem(slot,v);inv.setChanged();p.containerMenu.broadcastChanges();}};expectedItem=stack.copy();}
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
                phase=2;index=0;return false;
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
                phase=5;spawnIndex=0;return false;
            }
            if(phase==4){
                if(index>=0 && index<backups.size()){var b=backups.get(index--);apply(b.level,b.pos,b.state,b.be,b.slots);return false;}
                source.sendFailure(Component.literal("Откат остановлен: "+failure+". Применённые блоки возвращены из резервных копий."));return true;
            }
            if(phase==5){
                if(spawnIndex<spawned.size()){
                    int i=spawnIndex++;Entity entity=spawned.get(i);var undo=plan.spawned().get(i);
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
                if(spawnIndex<spawned.size()){Entity entity=spawned.get(spawnIndex);if(!entity.isRemoved())entity.discard();spawnIndex++;return false;}
                source.sendSystemMessage(Component.literal("Откат конструкции завершён: "+plan.cartId()+", блоков: "+applied));return true;
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
    private static void reserveHandlers(ServerLevel level,BlockPos pos,boolean requireSupported){
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
                if(owner!=net.neoforged.neoforge.items.ItemStackHandler.class && owner!=net.neoforged.neoforge.items.wrapper.InvWrapper.class && owner!=net.neoforged.neoforge.items.wrapper.CombinedInvWrapper.class)return false;
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
    private static void apply(ServerLevel level,BlockPos pos,BlockState state,CompoundTag be,CompoundTag slots){
        level.setBlock(pos,state,net.minecraft.world.level.block.Block.UPDATE_CLIENTS|net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE|net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS);
        if(!level.getBlockState(pos).equals(state))throw new IllegalStateException("Блок отклонил восстановление: "+pos);
        if(be!=null&&!NbtSerde.readBlockEntity(level,pos,be))throw new IllegalStateException("NBT блока не восстановлен: "+pos);
        if(slots!=null&&!ContainerSlotSnapshot.apply(level,pos,slots))throw new IllegalStateException("Инвентарь блока не восстановлен: "+pos);
        reserveHandlers(level,pos,true);
    }
}

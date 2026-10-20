package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Detached, bounded plan prepared entirely off the server thread. */
public record CartRollbackPlan(UUID cartId, long checkpointId, long cutoffId, long checkpointTs,
                               CompoundTag target, CompoundTag latestForm, List<BlockUndo> blocks,
                               List<ItemLocation> itemLocations,List<SpawnUndo> spawned,List<CartMenuTransferPlan.SlotUndo> recipients,List<CartAutomationTransferPlan.SlotUndo> automation,List<CreateWorldItemTransferPlan.Undo> worldItems,List<CreateWorldCreativeTransferPlan.IO> worldCreative,Set<UUID> mixedDrops,long bytes) {
    public CartRollbackPlan(UUID cartId,long checkpointId,long cutoffId,long checkpointTs,CompoundTag target,CompoundTag latestForm,List<BlockUndo> blocks,List<ItemLocation> itemLocations,List<SpawnUndo> spawned,List<CartMenuTransferPlan.SlotUndo> recipients,long bytes){this(cartId,checkpointId,cutoffId,checkpointTs,target,latestForm,blocks,itemLocations,spawned,recipients,List.of(),List.of(),List.of(),Set.of(),bytes);}
    public record BlockUndo(String dimension, BlockPos pos, String before, String after,
                            CompoundTag beforeTag, CompoundTag afterTag, CompoundTag be, CompoundTag slots,
                            CompoundTag afterBe, CompoundTag afterSlots) {}
    public record SpawnUndo(String dimension,UUID id,String type,CompoundTag nbt,CompoundTag item) {}
    public record ItemLocation(String dimension,BlockPos pos) {}
    private record Key(String dim, BlockPos pos) {}
    private static final int PAGE = 256, MAX_ROWS = 100_000;
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    public static boolean checkpoint(ActionType t) {
        return t == ActionType.CART_ASSEMBLE || t == ActionType.CART_PLACE || t == ActionType.CART_PACK || t == ActionType.CART_DISASSEMBLE || t == ActionType.CART_REMOVE || t == ActionType.CART_ROLLBACK;
    }
    public static CartRollbackPlan prepare(LogStorage storage, UUID id, long checkpointId) {
        LogQuery q = new LogQuery(); q.dim = "*"; q.cartId = id; q.requireDetails = true; q.afterId = checkpointId - 1; q.untilTs=Long.MAX_VALUE; q.limit = 1;
        List<LogEntry> found = storage.query(q);
        if (found.isEmpty() || found.getFirst().id != checkpointId || !id.equals(CartAuditContext.id(found.getFirst().source))) throw new IllegalArgumentException("Контрольная точка не найдена");
        LogEntry checkpoint = found.getFirst();
        if (!checkpoint(checkpoint.type)) throw new IllegalArgumentException("Нужна запись сборки, установки, упаковки, разборки или удаления");
        CompoundTag target = NbtSerde.fromSnbt(checkpoint.beAfter);
        if (target == null || target.getInt("Format") != 1) throw new IllegalArgumentException("Нет полного снимка контрольной точки");
        CompoundTag latest = target;
        LinkedHashMap<Key, BlockUndo> blocks = new LinkedHashMap<>();
        LinkedHashMap<UUID,SpawnUndo> spawned=new LinkedHashMap<>();
        LinkedHashSet<ItemLocation> locations = new LinkedHashSet<>();
        CartMenuTransferPlan transfers=new CartMenuTransferPlan();
        CreateWorldItemTransferPlan world=new CreateWorldItemTransferPlan();
        CartAutomationTransferPlan automation=new CartAutomationTransferPlan(world);
        CreateMixedDropTransferPlan mixed=new CreateMixedDropTransferPlan(id,world,transfers);
        List<Runnable> worldHistory=new ArrayList<>();
        // Cursor IDs follow capture order even when the system clock moves backward.
        q.afterId = 0; q.sinceTs = 0; q.untilTs = Long.MAX_VALUE; q.beforeId = Long.MAX_VALUE; q.limit = PAGE;
        long bytes = target.sizeInBytes(), cutoff = checkpointId; int rows = 0; boolean latestFound = false;
        scan: while (true) {
            List<LogEntry> page = storage.queryReverse(q);
            if (page.isEmpty()) break;
            long next = q.beforeId;
            for (LogEntry e : page) {
                if (e.id <= checkpointId) break scan;
                if (!id.equals(CartAuditContext.id(e.source))) throw new IllegalStateException("Хранилище вернуло запись другой конструкции");
                if(e.type==null)throw new IllegalStateException("Запись истории не содержит тип действия");
                if (++rows > MAX_ROWS) throw new IllegalStateException("Больше 100000 записей: выберите более позднюю контрольную точку");
                cutoff = Math.max(cutoff, e.id); next = Math.min(next, e.id);
                if (e.type == ActionType.CREATE_DEPLOYER_ACTION) throw new IllegalStateException("В периоде работал активатор: нужен совместный откат механизма, предметов и его целей");
                if(e.type==ActionType.CART_TRANSFER && CartAuditContext.phase(e.source).equals("actor_result")){
                    var result=NbtSerde.fromSnbt(e.beAfter);
                    if(result!=null&&Set.of("world_item_merge","mixed_world_item_pickup").contains(result.getString("Operation"))){
                        var old=NbtSerde.fromSnbt(e.beBefore);String dimension=e.dim;long position=new BlockPos(e.x,e.y,e.z).asLong();
                        if(old==null)throw new IllegalStateException("Нет исходного снимка смешанного дропа");
                        bytes+=old.sizeInBytes()+result.sizeInBytes();
                        worldHistory.add(()->mixed.add(old,result,dimension,position));
                    }
                    else if(result==null || !result.getBoolean("Complete"))throw new IllegalStateException("В периоде актор завершился с ошибкой: неполная операция не откатывается отдельно");
                }
                if (e.type == ActionType.CART_TRANSFER) {
                    var io=NbtSerde.fromSnbt(e.beAfter);
                    if(CartAuditContext.phase(e.source).equals("storage_io") || io!=null && io.getString("Kind").equals("storage_io"))
                        throw new IllegalStateException("В периоде использовано бесконечное хранилище: нужен совместный откат выданных и поглощённых ресурсов");
                }
                if (e.type == ActionType.CART_ROLLBACK) throw new IllegalStateException("Этот период уже пересекает откат; выберите контрольную точку после него");
                if (!latestFound && checkpoint(e.type)) {
                    latest = NbtSerde.fromSnbt(e.beAfter); latestFound = true;
                    if (latest == null) throw new IllegalStateException("Неполная история переходов");
                    bytes += latest.sizeInBytes();
                }
                if(e.type==ActionType.ENTITY_SPAWN) {
                    if(!Set.of("minecraft:item","create:super_glue").contains(e.entityType) || e.entityUuid==null)throw new IllegalStateException("В периоде создана внешняя сущность; нужен совместный откат сущностей");
                    var nbt=NbtSerde.fromSnbt(e.entityNbt);var item=NbtSerde.fromSnbt(e.itemStackNbt);
                    if(nbt==null || e.entityType.equals("minecraft:item")&&(item==null||item.isEmpty()))throw new IllegalStateException("Нет снимка связанного дропа/клея");
                    spawned.put(e.entityUuid,new SpawnUndo(e.dim,e.entityUuid,e.entityType,nbt,item));bytes+=nbt.sizeInBytes()+(item==null?0:item.sizeInBytes());
                }
                if(e.type==ActionType.ITEM_PICKUP && CartAuditContext.phase(e.source).equals("loot_pickup")){
                    var before=NbtSerde.fromSnbt(e.playerInvBefore);var after=NbtSerde.fromSnbt(e.playerInvAfter);
                    // Split ITEMS stores the drop UUID inside the recipient envelope, not an entity column.
                    if(before==null || after==null || !after.getString("Kind").equals("loot_items") || !after.hasUUID("Drop") || e.entityUuid!=null&&!e.entityUuid.equals(after.getUUID("Drop")) || !after.hasUUID("Player") || e.actorUuid!=null&&!Objects.equals(e.actorUuid,after.getUUID("Player")))throw new IllegalStateException("Подбор дропа не содержит связанный снимок получателя");
                    bytes+=before.sizeInBytes()+after.sizeInBytes();
                    worldHistory.add(()->transfers.manifest(before,after));
                }
                if(Set.of(ActionType.ENTITY_DEATH,ActionType.ENTITY_ATTACK).contains(e.type) && !"create:contraption".equals(e.entityType))throw new IllegalStateException("В периоде изменена внешняя сущность; нужен совместный откат сущностей");
                if (e.type == ActionType.CART_CONTENT_CHANGE) {
                    CompoundTag after = NbtSerde.fromSnbt(e.beAfter);
                    if(after==null || !after.contains("External") || after.getBoolean("Incomplete"))throw new IllegalStateException("Неполная запись изменения груза");
                    if (after.getBoolean("External")) bytes+=after.getString("TransferKind").equals("automation")?automation.cargo(NbtSerde.fromSnbt(e.beBefore),after):transfers.cargo(NbtSerde.fromSnbt(e.beBefore),after);
                }
                if(e.type==ActionType.CART_TRANSFER && Set.of("menu_transfer","depot_swap").contains(CartAuditContext.phase(e.source))){
                    var before=NbtSerde.fromSnbt(e.beBefore);var after=NbtSerde.fromSnbt(e.beAfter);
                    if(before==null||after==null)throw new IllegalStateException("Нет снимка обмена получателя");
                    bytes+=before.sizeInBytes()+after.sizeInBytes();
                    worldHistory.add(()->transfers.manifest(before,after));
                }
                if(e.type==ActionType.CART_TRANSFER && CartAuditContext.phase(e.source).equals("automation_transfer")){
                    var manifest=NbtSerde.fromSnbt(e.beAfter);
                    bytes+=automation.manifest(NbtSerde.fromSnbt(e.beBefore),manifest);
                    UUID transfer=manifest.getUUID("Transfer");worldHistory.add(()->automation.validateTransfer(transfer,Set.of(id)));
                }
                if (e.type == ActionType.BLOCK_BREAK || e.type == ActionType.BLOCK_PLACE || e.type == ActionType.BLOCK_INTERACT || e.type == ActionType.BLOCK_ENTITY_NBT_CHANGE) {
                    var pos = new BlockPos(e.x, e.y, e.z); var key = new Key(e.dim, pos); var previous = blocks.get(key);
                    CompoundTag before = NbtSerde.fromSnbt(e.blockBefore), after = previous == null ? NbtSerde.fromSnbt(e.blockAfter) : previous.afterTag;
                    if (before == null || after == null) throw new IllegalStateException("Неполный снимок блока " + pos);
                    CompoundTag be = NbtSerde.fromSnbt(e.beBefore), slots = NbtSerde.fromSnbt(e.containerSlotsBefore);
                    CompoundTag afterBe = previous == null ? NbtSerde.fromSnbt(e.beAfter) : previous.afterBe;
                    CompoundTag afterSlots = previous == null ? NbtSerde.fromSnbt(e.containerSlotsAfter) : previous.afterSlots;
                    BlockUndo undo = new BlockUndo(e.dim, pos, e.blockBefore, previous == null ? e.blockAfter : previous.after, before, after, be, slots, afterBe, afterSlots);
                    blocks.put(key, undo); bytes += before.sizeInBytes() + after.sizeInBytes() + (be == null ? 0 : be.sizeInBytes()) + (slots == null ? 0 : slots.sizeInBytes()) + (afterBe == null ? 0 : afterBe.sizeInBytes()) + (afterSlots == null ? 0 : afterSlots.sizeInBytes());
                }
                if ((e.type == ActionType.CONTAINER_PUT || e.type == ActionType.CONTAINER_TAKE) && locations.size() < 64) locations.add(new ItemLocation(e.dim,new BlockPos(e.x,e.y,e.z)));
                if (bytes > MAX_BYTES) throw new IllegalStateException("План превышает 128 MiB; выберите более позднюю контрольную точку");
            }
            if (next >= q.beforeId) throw new IllegalStateException("Курсор истории не продвигается");
            q.beforeId = next;
            if (page.size() < PAGE) break;
        }
        String form = target.getString("Form");
        if (!Set.of("entity", "item", "blocks", "removed").contains(form)) throw new IllegalStateException("Неизвестная форма конструкции");
        if(Set.of("entity","blocks").contains(form) && (!target.getCompound("Cart").hasUUID("UUID") || target.getCompound("Cart").getString("id").isEmpty()))throw new IllegalStateException("Отсутствует снимок базовой вагонетки");
        if (form.equals("entity")) {
            validateEntity(target.getCompound("Entity"));
            var audit=target.getCompound("Entity").getCompound("Contraption").getCompound(CartAuditState.KEY);
            if(!audit.hasUUID("Id") || !id.equals(audit.getUUID("Id")))throw new IllegalStateException("ID в снимке конструкции не совпадает с историей");
        }
        if(form.equals("item")) {
            var audit=target.getCompound("Item").getCompound("components").getCompound("create:minecart_contraption_data").getCompound(CartAuditState.KEY);
            if(!audit.hasUUID("Id") || !id.equals(audit.getUUID("Id")))throw new IllegalStateException("ID предмета не совпадает с историей");
        }
        for(var action:worldHistory)action.run();
        var recipients=transfers.finish();transfers.verifyLootSources(spawned.keySet());
        var slots=automation.finish(id);
        var worldCreative=automation.worldCreative();
        for(var io:worldCreative){
            var address=io.address();var block=new Key(address.getString("Dimension"),BlockPos.of(address.getLong("Pos")));
            if(blocks.containsKey(block)||slots.stream().anyMatch(slot->slot.address().getString("Dimension").equals(block.dim())
                    &&slot.address().getLong("Pos")==address.getLong("Pos")))
                throw new IllegalStateException("Creative источник изменён как блок или конечный слот: нужен более поздний checkpoint");
        }
        var worldItems=new ArrayList<>(world.finish());
        var mixedById=new HashMap<UUID,CreateWorldItemTransferPlan.Undo>();
        for(var endpoint:worldItems)if(mixed.entities().contains(endpoint.entity()))mixedById.put(endpoint.entity(),endpoint);
        List<SpawnUndo> effects=new ArrayList<>();for(var drop:spawned.values()){
            var remaining=transfers.remainingLoot(drop.id,drop.item);
            var mixedEndpoint=mixedById.get(drop.id);
            if(mixedEndpoint!=null){
                if(!remaining.equals(mixedEndpoint.before().getCompound("Item")))
                    throw new IllegalStateException("Дроп до слияния не совпадает с учтёнными подборами");
                remaining=mixedEndpoint.after().getCompound("Item").copy();
                effects.add(new SpawnUndo(drop.dimension,drop.id,drop.type,drop.nbt,remaining));
                continue;
            }
            for(var worldItem:worldItems)if(worldItem.entity().equals(drop.id)){
                var before=worldItem.before().getCompound("Item");var after=worldItem.after().getCompound("Item");
                var component=before.copy();component.remove("count");var expected=remaining.copy();expected.remove("count");
                if(!component.equals(expected))throw new IllegalStateException("Дроп изменил компоненты перед всасыванием");
                int absorbed=itemCount(before)-itemCount(after),left=itemCount(remaining)-absorbed;
                if(absorbed<=0||left<0)throw new IllegalStateException("Нарушен баланс созданного и всосанного дропа");
                remaining=left==0?new CompoundTag():remaining.copy();if(left>0)remaining.putInt("count",left);
            }
            effects.add(new SpawnUndo(drop.dimension,drop.id,drop.type,drop.nbt,remaining));
        }
        worldItems.removeIf(worldItem->spawned.containsKey(worldItem.entity())&&!mixed.entities().contains(worldItem.entity()));
        return new CartRollbackPlan(id, checkpointId, cutoff, checkpoint.ts, target, latest, List.copyOf(blocks.values()), List.copyOf(locations),List.copyOf(effects),recipients,slots,List.copyOf(worldItems),worldCreative,mixed.entities(),bytes);
    }
    private static void validateEntity(CompoundTag entity) {
        if (!entity.hasUUID("UUID") || !entity.getCompound("Contraption").getString("Type").equals("create:mounted")) throw new IllegalStateException("Некорректный снимок вагонеточной конструкции");
        if (entity.contains("OnCoupling") || !entity.getList("Passengers", 10).isEmpty() || !entity.getCompound("Contraption").getList("SubContraptions",10).isEmpty())
            throw new IllegalStateException("Связанные вагонетки или вложенные конструкции требуют совместного отката; одиночный откат остановлен");
    }
    private static int itemCount(CompoundTag tag){return tag.isEmpty()?0:tag.contains("count",3)?tag.getInt("count"):1;}
}

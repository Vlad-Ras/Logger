package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Detached, bounded plan prepared entirely off the server thread. */
public record CartRollbackPlan(UUID cartId, long checkpointId, long cutoffId, long checkpointTs,
                               CompoundTag target, CompoundTag latestForm, List<BlockUndo> blocks,
                               List<ItemLocation> itemLocations,List<SpawnUndo> spawned,List<CartMenuTransferPlan.SlotUndo> recipients,List<CartAutomationTransferPlan.SlotUndo> automation,long bytes) {
    public CartRollbackPlan(UUID cartId,long checkpointId,long cutoffId,long checkpointTs,CompoundTag target,CompoundTag latestForm,List<BlockUndo> blocks,List<ItemLocation> itemLocations,List<SpawnUndo> spawned,List<CartMenuTransferPlan.SlotUndo> recipients,long bytes){this(cartId,checkpointId,cutoffId,checkpointTs,target,latestForm,blocks,itemLocations,spawned,recipients,List.of(),bytes);}
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
        CartAutomationTransferPlan automation=new CartAutomationTransferPlan();
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
                    if(after==null || !after.getString("Kind").equals("loot_items") || !after.hasUUID("Drop") || e.entityUuid!=null&&!e.entityUuid.equals(after.getUUID("Drop")) || !after.hasUUID("Player") || !Objects.equals(e.actorUuid,after.getUUID("Player")))throw new IllegalStateException("Подбор дропа не содержит связанный снимок получателя");
                    bytes+=transfers.manifest(before,after);
                }
                if(Set.of(ActionType.ENTITY_DEATH,ActionType.ENTITY_ATTACK).contains(e.type) && !"create:contraption".equals(e.entityType))throw new IllegalStateException("В периоде изменена внешняя сущность; нужен совместный откат сущностей");
                if (e.type == ActionType.CART_CONTENT_CHANGE) {
                    CompoundTag after = NbtSerde.fromSnbt(e.beAfter);
                    if(after==null || !after.contains("External") || after.getBoolean("Incomplete"))throw new IllegalStateException("Неполная запись изменения груза");
                    if (after.getBoolean("External")) bytes+=after.getString("TransferKind").equals("automation")?automation.cargo(NbtSerde.fromSnbt(e.beBefore),after):transfers.cargo(NbtSerde.fromSnbt(e.beBefore),after);
                }
                if(e.type==ActionType.CART_TRANSFER && CartAuditContext.phase(e.source).equals("menu_transfer"))bytes+=transfers.manifest(NbtSerde.fromSnbt(e.beBefore),NbtSerde.fromSnbt(e.beAfter));
                if(e.type==ActionType.CART_TRANSFER && CartAuditContext.phase(e.source).equals("automation_transfer"))bytes+=automation.manifest(NbtSerde.fromSnbt(e.beBefore),NbtSerde.fromSnbt(e.beAfter));
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
        var recipients=transfers.finish();transfers.verifyLootSources(spawned.keySet());
        List<SpawnUndo> effects=new ArrayList<>();for(var drop:spawned.values()){
            var remaining=transfers.remainingLoot(drop.id,drop.item);
            effects.add(new SpawnUndo(drop.dimension,drop.id,drop.type,drop.nbt,remaining));
        }
        return new CartRollbackPlan(id, checkpointId, cutoff, checkpoint.ts, target, latest, List.copyOf(blocks.values()), List.copyOf(locations),List.copyOf(effects),recipients,automation.finish(id),bytes);
    }
    private static void validateEntity(CompoundTag entity) {
        if (!entity.hasUUID("UUID") || !entity.getCompound("Contraption").getString("Type").equals("create:mounted")) throw new IllegalStateException("Некорректный снимок вагонеточной конструкции");
        if (entity.contains("OnCoupling") || !entity.getList("Passengers", 10).isEmpty() || !entity.getCompound("Contraption").getList("SubContraptions",10).isEmpty())
            throw new IllegalStateException("Связанные вагонетки или вложенные конструкции требуют совместного отката; одиночный откат остановлен");
    }
}

package com.roften.avilixlogger.core;

import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Readable, bounded details from detached mod evidence; never queries blocks or capabilities. */
public final class ModAuditDetails {
    private static final java.util.regex.Pattern SLOT_SUMMARY=java.util.regex.Pattern.compile("^container slot ([0-9]+); before=([0-9]+); after=([0-9]+)");
    private ModAuditDetails() {}
    public static String summary(ActionType type,CompoundTag before,CompoundTag after,int changes) {
        if(type==ActionType.CLAIM_ACTION)return claimSummary(before,after);
        if(after.contains("StorageOperation"))return storageSummary(after.getCompound("StorageOperation"));
        var flows=after.getList("ObservedFlows",Tag.TAG_COMPOUND);
        if(!flows.isEmpty()) {
            var flow=flows.getCompound(0);
            return flow.getCompound("FluidKey").getString("id")+": +"+flow.getLong("InMB")+" / −"+flow.getLong("OutMB")+" mB"+(flows.size()>1?"; ещё жидкостей: "+(flows.size()-1):"");
        }
        return "Изменений: "+changes;
    }
    public static String feedSummary(LogEntry row) {
        if(row.extra==null)return "";
        if(row.extra.startsWith("ui=")) {
            int end=row.extra.indexOf("|audit|",3);
            return row.extra.substring(3,end<0?Math.min(row.extra.length(),423):end);
        }
        if(row.extra.startsWith("container slot ")) {
            var match=SLOT_SUMMARY.matcher(row.extra);
            if(match.find())return "Слот "+match.group(1)+": "+match.group(2)+" → "+match.group(3);
        }
        return "";
    }
    public static String claimSummary(CompoundTag before,CompoundTag after) {
        boolean was=before.getBoolean("Claimed"),now=after.getBoolean("Claimed");
        String action=was==now?"изменён приват":now?"заприватил чанк":"расприватил чанк";
        var location=after.contains("ChunkX")?after:before;
        return action+" ["+location.getInt("ChunkX")+", "+location.getInt("ChunkZ")+"]";
    }
    public static String storageSummary(CompoundTag operation) {
        String direction=switch(operation.getString("Direction")){case "insert"->"добавил в хранилище";case "extract"->"извлёк из хранилища";default->"частичное изменение хранилища";};
        return direction+" "+operation.getLong("TransferredMB")+" mB; опыт игрока "+signed(operation.getLong("PlayerExperienceDelta"));
    }
    public static List<Component> describe(LogEntry row) {
        boolean slotRow=row.extra!=null&&row.extra.startsWith("container slot ");
        if(!slotRow&&(row.source==null||!row.source.startsWith("mod:")))return List.of();
        if(row.beAfter==null||row.beAfter.length()>2_097_152||row.beBefore!=null&&row.beBefore.length()>2_097_152)return List.of();
        var after=NbtSerde.fromSnbt(row.beAfter);if(after==null)return List.of();
        if(slotRow&&after.contains("StorageSlot")) {
            var slot=after.getCompound("StorageSlot");var address=slot.getCompound("Address");
            return List.of(Component.literal("Изменение слота "+slot.getInt("LocalSlot")+" (в общем интерфейсе: "+slot.getInt("Slot")+")"),
                Component.literal("Количество: "+slot.getInt("BeforeCount")+" → "+slot.getInt("AfterCount")),
                Component.literal("Блок: "+(address.isEmpty()?row.blockAfter:address.getCompound("BlockState").getString("Name"))),
                Component.literal("Координаты: "+row.x+" "+row.y+" "+row.z+" · "+row.dim),
                Component.literal(slot.getBoolean("PhysicalAddressKnown")?"Адрес физического контейнера подтверждён.":"Адрес интерфейса; внутренний физический контейнер не определён."),
                Component.literal("До: "+value(slot.get("Before"))),Component.literal("После: "+value(slot.get("After"))));
        }
        var before=row.beBefore==null?new CompoundTag():NbtSerde.fromSnbt(row.beBefore);if(before==null)before=new CompoundTag();
        if(!after.contains("Changes")&&!after.contains("StorageOperation")&&row.type!=ActionType.CLAIM_ACTION)return List.of();
        var lines=new ArrayList<Component>();
        lines.add(Component.literal("Источник: "+row.source));
        lines.add(Component.literal("Кто: "+(row.actorUuid==null?"Сервер / механизм (игрок не установлен)":row.actorName+" · "+row.actorUuid)));
        var address=after.contains("X")?after:before;
        if(address.contains("Block"))lines.add(Component.literal("Блок: "+address.getString("Block")));
        if(address.contains("X"))lines.add(Component.literal("Координаты блока: "+address.getInt("X")+" "+address.getInt("Y")+" "+address.getInt("Z")+" · "+row.dim));
        var operation=after.getCompound("StorageOperation");
        if(!operation.isEmpty()) {
            lines.add(Component.literal(storageSummary(operation)));
            lines.add(Component.literal("Способ: люк опыта; позиция люка: "+operation.getInt("HatchX")+" "+operation.getInt("HatchY")+" "+operation.getInt("HatchZ")));
            lines.add(Component.literal("Опыт игрока: "+before.getCompound("PlayerExperience").getLong("Points")+" → "+after.getCompound("PlayerExperience").getLong("Points")));
        }
        if(row.type==ActionType.CLAIM_ACTION) {
            lines.add(Component.literal(claimSummary(before,after)+" · "+row.dim));
            lines.add(Component.literal("Владелец до: "+owner(before)+"; после: "+owner(after)));
            if(after.hasUUID("PerformedAsUUID"))lines.add(Component.literal("Выполнение от имени: "+after.getUUID("PerformedAsUUID")));
            if(row.actorUuid==null)lines.add(Component.literal("Владелец привата не считается инициатором без подтверждения."));
            lines.add(Component.literal("Переход: кнопка TP или действие «Перейти к записи»."));
        }
        var flows=after.getList("ObservedFlows",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(64,flows.size());i++) {
            var flow=flows.getCompound(i);lines.add(Component.literal("Жидкость "+flow.getCompound("FluidKey").getString("id")+": добавлено "+flow.getLong("InMB")+" mB; извлечено "+flow.getLong("OutMB")+" mB"));
        }
        var differences=after.getList("Changes",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(120,differences.size());i++) {
            var change=differences.getCompound(i);lines.add(Component.literal(change.getString("Path")+": "+value(change.get("Before"))+" → "+value(change.get("After"))));
        }
        if(differences.size()>120||after.getBoolean("ChangesMayBeTruncated"))lines.add(Component.literal("Сводка ограничена; полные сохранённые снимки доступны во вкладке JSON."));
        if(after.getBoolean("NativeThrew"))lines.add(Component.literal("Игровая операция завершилась исключением; показаны наблюдаемые изменения."));
        return List.copyOf(lines);
    }
    private static String owner(CompoundTag tag){return tag.contains("OwnerUUID")?tag.getString("OwnerUUID"):"нет";}
    private static String signed(long value){return(value>0?"+":"")+value;}
    private static String value(Tag tag) {
        if(tag==null)return "∅";
        String text=tag.getAsString().replace('\n',' ').replace('\r',' ');
        return text.length()>280?text.substring(0,280)+"…":text;
    }
}

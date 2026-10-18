package com.roften.avilixlogger.compat.create;

import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Validates both sides and item conservation without touching live inventories or registries. */
public final class CartMenuTransferPlan {
    public record SlotUndo(UUID player, int slot, CompoundTag before, CompoundTag after) {}
    private record Key(UUID player,int slot) {}
    private record CargoKey(boolean base,long position,int slot) {}
    private record Cargo(CompoundTag before,CompoundTag after) {}
    private record Loot(CompoundTag first,CompoundTag remaining) {}
    private static final class Group {
        CompoundTag before,after;
        final Map<Integer,Cargo> cargo=new HashMap<>();
    }
    private final Map<UUID,Group> groups=new HashMap<>();
    private final LinkedHashMap<Key,SlotUndo> slots=new LinkedHashMap<>();
    private final List<UUID> order=new ArrayList<>();
    private final Map<UUID,Loot> loot=new HashMap<>();
    public long cargo(CompoundTag before,CompoundTag after) {
        if (before==null || after==null || !before.hasUUID("Transfer") || !after.hasUUID("Transfer") || !before.getUUID("Transfer").equals(after.getUUID("Transfer"))) throw new IllegalStateException("Обмен грузом не содержит связанный снимок получателя");
        int index=after.getInt("TransferIndex");
        if (!before.contains("TransferIndex") || !after.contains("TransferIndex") || index<0 || index>=512 || before.getInt("TransferIndex")!=index) throw new IllegalStateException("Некорректный номер изменения груза");
        var group=groups.computeIfAbsent(after.getUUID("Transfer"),unused->new Group());
        if(group.cargo.putIfAbsent(index,new Cargo(before,after))!=null)throw new IllegalStateException("Повтор изменения в обмене грузом");
        return before.sizeInBytes()+after.sizeInBytes();
    }
    public long manifest(CompoundTag before,CompoundTag after) {
        if(before==null || after==null || !after.hasUUID("Transfer") || !before.hasUUID("Transfer") || !before.getUUID("Transfer").equals(after.getUUID("Transfer")))throw new IllegalStateException("Неполная запись обмена через меню");
        UUID id=after.getUUID("Transfer");var group=groups.computeIfAbsent(id,unused->new Group());
        if(group.after!=null)throw new IllegalStateException("Повтор связанного снимка получателя");
        group.before=before;group.after=after;order.add(id);
        return before.sizeInBytes()+after.sizeInBytes();
    }
    public List<SlotUndo> finish() {
        if(order.size()!=groups.size())throw new IllegalStateException("Снимок получателя отсутствует в истории");
        // Manifests arrive newest first, matching the reverse history cursor.
        for(UUID id:order)validate(groups.get(id));
        return slots.values().stream().filter(slot->!slot.before.equals(slot.after)).toList();
    }
    public void verifyLootSources(Set<UUID> created){if(!created.containsAll(loot.keySet()))throw new IllegalStateException("Подобранный дроп создан до контрольной точки или его снимок отсутствует");}
    public CompoundTag remainingLoot(UUID drop,CompoundTag initial){
        var state=loot.get(drop);if(state==null)return initial;
        if(!state.first.equals(initial))throw new IllegalStateException("Дроп изменён до подбора; нужна полная история его перемещений и объединений");
        return state.remaining;
    }
    private void validate(Group group) {
        CompoundTag b=group.before,a=group.after;
        boolean pickup=a.getString("Kind").equals("loot_items");
        if(b.getInt("Format")!=1 || a.getInt("Format")!=1 || !b.getString("Kind").equals(a.getString("Kind")) || !pickup&&!a.getString("Kind").equals("menu_items") || !b.getBoolean("Complete") || !a.getBoolean("Complete") || !b.hasUUID("Player") || !a.hasUUID("Player") || !b.getUUID("Player").equals(a.getUUID("Player")))throw new IllegalStateException("Обмен предметами не был полностью захвачен");
        int count=a.getInt("Changes");if(count<0 || count>512 || b.getInt("Changes")!=count || group.cargo.size()!=count)throw new IllegalStateException("В истории отсутствуют изменения одной из сторон обмена");
        Map<CompoundTag,Long> balance=new HashMap<>();Map<CargoKey,CompoundTag> last=new HashMap<>();
        if(pickup){
            if(count!=0 || !b.hasUUID("Drop") || !a.hasUUID("Drop") || !b.getUUID("Drop").equals(a.getUUID("Drop")) || !b.contains("Item",10) || !a.contains("Item",10))throw new IllegalStateException("Неполный снимок подобранного дропа");
            UUID drop=a.getUUID("Drop");var old=b.getCompound("Item");var now=a.getCompound("Item");var previous=loot.get(drop);
            if(old.isEmpty() || itemCount(old)<=itemCount(now))throw new IllegalStateException("Подбор не уменьшил связанный дроп");
            if(previous!=null&&!now.equals(previous.first))throw new IllegalStateException("Цепочка частичного подбора дропа прервана");
            loot.put(drop,new Loot(old,previous==null?now:previous.remaining));add(balance,old,-1);add(balance,now,1);
        }
        for(int i=0;i<count;i++) {
            Cargo c=group.cargo.get(i);if(c==null)throw new IllegalStateException("В истории отсутствует часть обмена");
            if(!c.before.getString("Kind").equals("item") || !c.after.getString("Kind").equals("item"))throw new IllegalStateException("Этот обмен требует адаптер жидкости");
            CargoKey key=new CargoKey(c.before.getBoolean("BaseCart"),c.before.getLong("LocalPos"),c.before.getInt("Slot"));
            if(!key.equals(new CargoKey(c.after.getBoolean("BaseCart"),c.after.getLong("LocalPos"),c.after.getInt("Slot"))))throw new IllegalStateException("Адрес груза изменился внутри обмена");
            CompoundTag previous=last.put(key,c.after.getCompound("Item"));
            if(previous!=null && !previous.equals(c.before.getCompound("Item")))throw new IllegalStateException("Цепочка изменений груза прервана");
            add(balance,c.before.getCompound("Item"),-1);add(balance,c.after.getCompound("Item"),1);
        }
        var before=b.getList("Slots",10);var after=a.getList("Slots",10);
        if(!b.contains("Slots",9) || !a.contains("Slots",9) || before.size()!=after.size() || before.size()>42 || count==0&&before.isEmpty())throw new IllegalStateException("Неполный снимок инвентаря получателя");
        Set<Integer> seen=new HashSet<>();
        for(int i=0;i<before.size();i++) {
            CompoundTag bs=before.getCompound(i),as=after.getCompound(i);int slot=as.getInt("Slot");
            if(!bs.contains("Slot") || !as.contains("Slot") || slot< (pickup?0:-1) || slot>=41 || slot!=bs.getInt("Slot") || !seen.add(slot))throw new IllegalStateException("Некорректный слот получателя");
            CompoundTag old=bs.getCompound("Item"),now=as.getCompound("Item");
            add(balance,old,-1);add(balance,now,1);
            Key key=new Key(a.getUUID("Player"),slot);var latest=slots.get(key);
            if(latest!=null && !now.equals(latest.before))throw new IllegalStateException("Груз получателя перемещён между обменами; нужен откат этого перемещения");
            slots.put(key,new SlotUndo(key.player,slot,old,latest==null?now:latest.after));
        }
        if(balance.values().stream().anyMatch(n->n!=0))throw new IllegalStateException("Обмен изменил количество или компоненты предметов; получатель не полностью учтён");
    }
    private static void add(Map<CompoundTag,Long> balance,CompoundTag item,int sign) {
        if(item.isEmpty())return;
        int count=item.contains("count")?item.getInt("count"):1;if(count<=0 || item.getString("id").isEmpty())throw new IllegalStateException("Некорректный предмет в снимке обмена");
        CompoundTag key=item.copy();key.remove("count");balance.merge(key,(long)sign*count,Long::sum);
    }
    private static int itemCount(CompoundTag item){return item.isEmpty()?0:item.contains("count")?item.getInt("count"):1;}
}

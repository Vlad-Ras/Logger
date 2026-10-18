package com.roften.avilixlogger.compat.create;

import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Detached validation of both sides, exact components and reverse slot/tank continuity. */
public final class CartAutomationTransferPlan {
    public record SlotUndo(CompoundTag address,CompoundTag before,CompoundTag after) {}
    private record Cargo(CompoundTag before,CompoundTag after) {}
    private record Cell(String dimension,long pos,String kind,int slot) {}
    private record Resource(String kind,CompoundTag components) {}
    private static final class Group {CompoundTag before,after;final Map<Integer,Cargo> cargo=new HashMap<>();}
    private final Map<UUID,Group> groups=new HashMap<>();private final List<UUID> order=new ArrayList<>();
    private final LinkedHashMap<Cell,SlotUndo> slots=new LinkedHashMap<>();
    public long cargo(CompoundTag b,CompoundTag a){
        UUID id=transfer(b,a);int i=a.getInt("TransferIndex");
        if(!b.contains("TransferIndex",3)||!a.contains("TransferIndex",3)||i<0||i>=512||b.getInt("TransferIndex")!=i||!b.getString("TransferKind").equals("automation")||!a.getString("TransferKind").equals("automation"))throw new IllegalStateException("Некорректная часть автоматического обмена");
        if(groups.computeIfAbsent(id,k->new Group()).cargo.putIfAbsent(i,new Cargo(b,a))!=null)throw new IllegalStateException("Повтор части автоматического обмена");
        return b.sizeInBytes()+a.sizeInBytes();
    }
    public long manifest(CompoundTag b,CompoundTag a){
        UUID id=transfer(b,a);var group=groups.computeIfAbsent(id,k->new Group());if(group.after!=null)throw new IllegalStateException("Повтор снимка автоматического обмена");
        group.before=b;group.after=a;order.add(id);return b.sizeInBytes()+a.sizeInBytes();
    }
    private static UUID transfer(CompoundTag b,CompoundTag a){
        if(b==null||a==null||!b.hasUUID("Transfer")||!a.hasUUID("Transfer")||!b.getUUID("Transfer").equals(a.getUUID("Transfer")))throw new IllegalStateException("Автоматический обмен не содержит связанных снимков");return a.getUUID("Transfer");
    }
    public List<SlotUndo> finish(UUID cart){
        if(order.size()!=groups.size())throw new IllegalStateException("В истории отсутствует снимок внешнего хранилища");
        for(UUID id:order)validate(groups.get(id),cart);
        return slots.values().stream().filter(s->!s.before.equals(s.after)).toList();
    }
    private void validate(Group group,UUID cart){
        var b=group.before;var a=group.after;
        if(b.getInt("Format")!=1||a.getInt("Format")!=1||!b.getString("Kind").equals("automation")||!a.getString("Kind").equals("automation")||!b.getBoolean("Complete")||!a.getBoolean("Complete")||!b.hasUUID("Cart")||!a.hasUUID("Cart")||!cart.equals(b.getUUID("Cart"))||!cart.equals(a.getUUID("Cart")))throw new IllegalStateException("Автоматический обмен не захвачен полностью или затронул несколько конструкций");
        int count=a.getInt("Changes");if(count<=0||count>512||b.getInt("Changes")!=count||group.cargo.size()!=count)throw new IllegalStateException("Отсутствует часть автоматического обмена");
        Map<Resource,Long> balance=new HashMap<>();Map<String,CompoundTag> cargoLast=new HashMap<>();
        for(int i=0;i<count;i++){
            Cargo c=group.cargo.get(i);if(c==null)throw new IllegalStateException("Отсутствует часть изменения груза");
            String kind=c.after.getString("Kind"),field=field(kind),index=kind.equals("item")?"Slot":"Tank";
            if(!kind.equals(c.before.getString("Kind"))||c.before.getLong("LocalPos")!=c.after.getLong("LocalPos")||c.before.getInt(index)!=c.after.getInt(index)||c.before.getBoolean("BaseCart")!=c.after.getBoolean("BaseCart")||!c.before.getBoolean("External")||!c.after.getBoolean("External")||c.before.getBoolean("Incomplete")||c.after.getBoolean("Incomplete"))throw new IllegalStateException("Адрес груза или полнота снимка изменены");
            String key=kind+":"+c.after.getBoolean("BaseCart")+":"+c.after.getLong("LocalPos")+":"+c.after.getInt(index);
            var previous=cargoLast.put(key,c.after.getCompound(field));if(previous!=null&&!previous.equals(c.before.getCompound(field)))throw new IllegalStateException("Цепочка автоматического изменения груза прервана");
            add(balance,kind,c.before.getCompound(field),-1);add(balance,kind,c.after.getCompound(field),1);
        }
        var before=b.getList("Slots",10);var after=a.getList("Slots",10);
        if(!b.contains("Slots",9)||!a.contains("Slots",9)||before.size()!=after.size()||before.size()>512)throw new IllegalStateException("Неполный снимок внешних слотов/баков");
        Set<Cell> seen=new HashSet<>();
        for(int i=0;i<before.size();i++){
            var old=before.getCompound(i);var now=after.getCompound(i);String kind=now.getString("Kind"),field=field(kind);
            if(!CartAutomationStorage.sameAddress(old,now)||!now.contains("Pos",4)||!now.contains("Slot",3)||now.getInt("Slot")<0||now.getInt("Slot")>=now.getInt("Size")||now.getInt("Limit")<0||now.getString("Dimension").isBlank()||now.getString("Handler").isBlank()||now.getString("BlockEntity").isBlank()||!now.contains("Block",10)||!validSide(now.getString("Side")))throw new IllegalStateException("Некорректный адрес внешнего хранилища");
            Cell key=new Cell(now.getString("Dimension"),now.getLong("Pos"),kind,now.getInt("Slot"));if(!seen.add(key))throw new IllegalStateException("Повтор внешнего слота/бака в обмене");
            add(balance,kind,old.getCompound(field),-1);add(balance,kind,now.getCompound(field),1);
            var latest=slots.get(key);if(latest!=null&&(!now.getCompound(field).equals(latest.before)||!CartAutomationStorage.sameAddress(now,latest.address)))throw new IllegalStateException("Внешний груз перемещён или адрес изменён между обменами");
            var address=now.copy();address.remove("Item");address.remove("Fluid");
            slots.put(key,new SlotUndo(latest==null?address:latest.address,old.getCompound(field),latest==null?now.getCompound(field):latest.after));
        }
        if(balance.values().stream().anyMatch(value->value!=0))throw new IllegalStateException("Автоматический обмен изменил количество или компоненты; обе стороны не полностью учтены");
    }
    private static boolean validSide(String side){return Set.of("none","down","up","north","south","west","east").contains(side);}
    private static String field(String kind){return switch(kind){case "item"->"Item";case "fluid"->"Fluid";default->throw new IllegalStateException("Неизвестный тип груза");};}
    private static void add(Map<Resource,Long> balance,String kind,CompoundTag stack,int sign){
        if(stack.isEmpty())return;String quantity=kind.equals("item")?"count":"amount";
        int count=stack.contains(quantity,3)?stack.getInt(quantity):kind.equals("item")?1:0;
        if(count<=0||stack.getString("id").isBlank())throw new IllegalStateException("Некорректный предмет/жидкость в обмене");
        var key=stack.copy();key.remove(quantity);balance.merge(new Resource(kind,key),(long)sign*count,Long::sum);
    }
}

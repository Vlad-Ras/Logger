package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.fluids.FluidStack;
import java.util.*;

/** Readable native cargo and external endpoint diffs; no dependency on Create classes. */
public final class CartLogDetails {
    private CartLogDetails(){}
    public static List<Component> describe(ServerLevel level,LogEntry row){
        if(CreateContraptionKind.id(row.source)==null)return List.of();
        var before=NbtSerde.fromSnbt(row.beBefore);var after=NbtSerde.fromSnbt(row.beAfter);if(after==null)return List.of();if(before==null){if(!after.getString("Kind").equals("lifecycle"))return List.of();before=new CompoundTag();}
        List<Component> lines=new ArrayList<>();String kind=after.getString("Kind");
        if(kind.equals("deployer_action")&&Set.of(1,2,3,4,5,6).contains(after.getInt("JointFormat"))){
            lines.add(Component.literal("Активатор · операция "+after.getUUID("Transfer")+" · "+(after.getBoolean("Complete")?"рука и цели связаны":"неполный снимок — откат запрещён")));
            lines.add(Component.literal("Груз: изменённых ячеек "+after.getList("Cargo",10).stream().filter(t->!((CompoundTag)t).getString("Kind").equals("storage_io")).count()));
            for(var raw:after.getList("Cargo",10)){var io=(CompoundTag)raw;if(io.getString("Kind").equals("storage_io"))lines.add(Component.literal(io.getString("Direction").equals("in")?"Бесконечное хранилище · поглощено: ":"Бесконечное хранилище · выдано: ").append(resource(level,io.getString("ResourceKind"),io.getCompound("Payload"))));}
            lines.add(Component.literal("Прямые изменения: блоков "+after.getList("World",10).size()+", сущностей "+after.getList("Targets",10).size()));
            if(after.contains("JointFailure"))lines.add(Component.literal(after.getString("JointFailure")));
            for(var raw:after.getList("Targets",10)){var target=(CompoundTag)raw;lines.add(Component.literal("Цель: "+target.getString("Type")+" · "+target.getUUID("Entity")+(target.getBoolean("Exists")?"":" · удалена")));}
        }
        if(kind.equals("structural")){
            lines.add(Component.literal("Структурная операция: "+after.getUUID("Operation")+" · "+(after.getBoolean("Complete")?"полный снимок":"неполный снимок — откат запрещён")));
            if(after.contains("Failure"))lines.add(Component.literal("Причина неполного снимка: "+after.getString("Failure")));
            lines.add(Component.literal("Конструкций: "+after.getList("Bodies",10).size()+" · блоков/контроллеров: "+after.getList("World",10).size()+" · дропов/клея: "+after.getList("Effects",10).size()));
            for(var raw:after.getList("Bodies",10)){var body=(CompoundTag)raw;lines.add(Component.literal(body.getString("ConstructionKind")+" · "+body.getUUID("Construction")+" · форма "+body.getString("Form")));}
            var oldWorld=before.getList("World",10);var newWorld=after.getList("World",10);
            for(int i=0;i<Math.min(32,Math.min(oldWorld.size(),newWorld.size()));i++){
                var b=oldWorld.getCompound(i);var a=newWorld.getCompound(i);String address=a.getString("Dimension")+" "+BlockPos.of(a.getLong("Pos")).toShortString();
                if(!b.getCompound("State").equals(a.getCompound("State")))lines.add(Component.literal("Блок "+address+": "+clip(b.getCompound("State").toString())+" → "+clip(a.getCompound("State").toString())));
                if(!b.getCompound("BE").equals(a.getCompound("BE")))lines.add(Component.literal((a.getBoolean("Controller")?"Контроллер ":"NBT блока ")+address+": "+clip(b.getCompound("BE").toString())+" → "+clip(a.getCompound("BE").toString())));
                if(!b.getCompound("Slots").equals(a.getCompound("Slots")))lines.add(Component.literal("Инвентарь "+address+": "+clip(b.getCompound("Slots").toString())+" → "+clip(a.getCompound("Slots").toString())));
                if(!b.getCompound("Runtime").equals(a.getCompound("Runtime")))lines.add(Component.literal("Состояние контроллера "+address+": "+clip(b.getCompound("Runtime").toString())+" → "+clip(a.getCompound("Runtime").toString())));
            }
            if(newWorld.size()>32)lines.add(Component.literal("Ещё блоков в полном снимке: "+(newWorld.size()-32)));
        }
        if(after.hasUUID("StorageEntity")&&CreateContraptionKind.fromSource(row.source)!=CreateContraptionKind.MINECART)
            lines.add(Component.literal("Сущность хранилища/вагона: "+after.getUUID("StorageEntity")));
        if(kind.equals("lifecycle")){
            lines.add(Component.literal("Форма конструкции: "+before.getString("Form")+" → "+after.getString("Form")));
            lines.add(Component.literal("Постоянный ID: "+after.getUUID("Construction")+" · "+after.getString("ConstructionKind")));
            if(after.hasUUID("Carriage"))lines.add(Component.literal("Постоянный ID вагона: "+after.getUUID("Carriage")+" · портал: "+after.getBoolean("Portal")));
            for(var raw:after.getList("RepresentationStatus",10)){
                var representation=(CompoundTag)raw;
                lines.add(Component.literal("Представление в "+representation.getString("Dimension")+": "+(representation.getBoolean("Loaded")?"загружено · "+representation.getUUID("Entity"):"выгружено")));
            }
            for(var relation:after.getList("Links",10)){var link=(CompoundTag)relation;lines.add(Component.literal("Связь "+link.getString("Relation")+": "+link.getUUID("Entity")+(link.hasUUID("Construction")?" · конструкция "+link.getUUID("Construction"):"")));}
        }else if(kind.equals("coupling")){
            lines.add(Component.literal("Изменение сцепки · операция "+after.getUUID("Operation")+" · "+(after.getBoolean("Complete")?"захвачена полностью":"неполный снимок")));
            lines.add(Component.literal("До: "+clip(before.getList("Carts",10).toString())+" → После: "+clip(after.getList("Carts",10).toString())));
        }else if(kind.equals("item")||kind.equals("fluid")){
            lines.add(Component.literal("Груз конструкции: "+(after.getBoolean("BaseCart")?"вагонетка":BlockPos.of(after.getLong("LocalPos")).toShortString())+", "+(kind.equals("item")?"слот "+after.getInt("Slot"):"бак "+after.getInt("Tank"))));
            delta(lines,level,kind,before,after);
            if(after.contains("Operation"))lines.add(Component.literal("Причина: "+switch(after.getString("Operation")){
                case "deployer_take" -> "активатор взял предмет из груза";
                case "deployer_return" -> "активатор вернул предмет в груз";
                case "block_break_loot" -> "механизм собрал добычу разрушенного блока";
                case "dispenser_refill" -> "пополнение раздатчика или выбрасывателя из груза";
                case "dropper_dispense" -> "выбрасыватель выдал предмет";
                case "dispenser_dispense" -> "раздатчик использовал предмет или вернул остаток";
                case "depot_swap" -> "обмен с рукой игрока на депо";
                default -> after.getString("Operation");}));
            if(after.hasUUID("Transfer"))lines.add(Component.literal("Операция: "+after.getUUID("Transfer")));
        }else if(kind.equals("storage_io")){
            lines.add(Component.literal("Бесконечное хранилище: "+BlockPos.of(after.getLong("LocalPos")).toShortString()+", "+(after.getString("ResourceKind").equals("item")?"слот ":"бак ")+after.getInt("Slot")));
            lines.add(Component.literal(after.getString("Direction").equals("in")?"Поглощено: ":"Выдано: ").append(resource(level,after.getString("ResourceKind"),after.getCompound("Payload"))));
            lines.add(Component.literal("Источник: ").append(resource(level,after.getString("ResourceKind"),after.getCompound("Supplied"))));
            lines.add(Component.literal(after.hasUUID("Transfer")&&after.getInt("IOFormat")==1?"Выдача/поглощение связаны с общим снимком передачи; источник проверяется при совместном откате":"Нет полного снимка получателя/отправителя; отдельный откат запрещён"));
            if(after.hasUUID("Transfer"))lines.add(Component.literal("Операция: "+after.getUUID("Transfer")));
        }else if(kind.equals("menu_items")){
            lines.add(Component.literal("Обмен с игроком: "+after.getUUID("Player")+" · операция "+after.getUUID("Transfer")));
            lines.add(Component.literal(after.getBoolean("Complete")?"Снимки груза, инвентаря и курсора связаны":"Неполный снимок обмена — откат запрещён"));
            var b=before.getList("Slots",10);var a=after.getList("Slots",10);
            for(int i=0;i<Math.min(42,Math.min(b.size(),a.size()));i++){
                int slot=a.getCompound(i).getInt("Slot");lines.add(Component.literal(slot==-1?"Курсор игрока":"Слот игрока: "+slot));
                delta(lines,level,"item",b.getCompound(i),a.getCompound(i));
            }
        }else if(kind.equals("automation")){
            var cargoBefore=before.getList("Cargo",10);var cargoAfter=after.getList("Cargo",10);
            for(int i=0;i<Math.min(100,Math.min(cargoBefore.size(),cargoAfter.size()));i++){
                var b=cargoBefore.getCompound(i);var a=cargoAfter.getCompound(i);String resource=a.getString("Kind");
                lines.add(Component.literal("Участник: "+a.getString("ConstructionKind")+" · "+a.getUUID("Construction")+" · хранилище "+a.getUUID("StorageEntity")
                        +" · "+BlockPos.of(a.getLong("LocalPos")).toShortString()+" · "+(resource.equals("item")?"слот "+a.getInt("Slot"):"бак "+a.getInt("Tank"))));
                if(a.hasUUID("Carriage"))lines.add(Component.literal("Постоянный ID вагона: "+a.getUUID("Carriage")));
                delta(lines,level,resource,b,a);
            }
            var worldBefore=before.getList("Entities",10);var worldAfter=after.getList("Entities",10);
            for(int i=0;i<Math.min(200,Math.min(worldBefore.size(),worldAfter.size()));i++){
                var b=worldBefore.getCompound(i);var a=worldAfter.getCompound(i);
                lines.add(Component.literal("Предмет в мире: "+a.getString("EntityType")+" · "+a.getUUID("WorldEntity")+" · "+a.getString("Dimension")+" "+BlockPos.of(a.getLong("Pos")).toShortString()));
                delta(lines,level,"item",b,a);
            }
            lines.add(Component.literal((after.getBoolean("Mounted")?"Механизм на конструкции: ":"Автоматика: ")+after.getString("Machine")+" · "+after.getString("MachineDim")+" "+BlockPos.of(after.getLong("MachinePos")).toShortString()));
            lines.add(Component.literal("Операция: "+after.getUUID("Transfer")+" · "+(after.getBoolean("Complete")?"обе стороны захвачены":"неполный снимок — откат запрещён")));
            var b=before.getList("Slots",10);var a=after.getList("Slots",10);int count=Math.min(200,Math.min(b.size(),a.size()));
            for(int i=0;i<count;i++){var old=b.getCompound(i);var now=a.getCompound(i);String k=now.getString("Kind");
                lines.add(Component.literal((now.getBoolean("Unresolved")?"Неподтверждённый адрес получателя: ":"Хранилище: ")+now.getString("Dimension")+" "+BlockPos.of(now.getLong("Pos")).toShortString()+" · "+(k.equals("item")?"слот ":"бак ")+now.getInt("Slot")));
                if(now.hasUUID("ContainerEntity"))lines.add(Component.literal("Получатель: "+(now.contains("EntityName")?now.getString("EntityName"):now.getString("EntityType"))+" · "+now.getUUID("ContainerEntity")));
                if(now.contains("DoubleChest",10))lines.add(Component.literal("Двойной сундук: вторая половина "+BlockPos.of(now.getCompound("DoubleChest").getLong("Pos")).toShortString()+" · физический слот "+(now.getInt("Slot")%27)+" по адресу "+BlockPos.of(now.getInt("Slot")<27?now.getLong("Pos"):now.getCompound("DoubleChest").getLong("Pos")).toShortString()));
                delta(lines,level,k,old,now);
            }
            if(a.size()>count)lines.add(Component.literal("… ещё "+(a.size()-count)+" изменений; полный снимок доступен в JSON"));
        }
        return List.copyOf(lines);
    }
    private static void delta(List<Component> lines,ServerLevel level,String kind,CompoundTag before,CompoundTag after){
        String field=kind.equals("item")?"Item":"Fluid";var b=before.getCompound(field);var a=after.getCompound(field);
        lines.add(Component.literal("До: ").append(resource(level,kind,b)).append(" → После: ").append(resource(level,kind,a)));
        if(!b.getCompound("components").equals(a.getCompound("components"))){String old=b.getCompound("components").toString(),now=a.getCompound("components").toString();lines.add(Component.literal("Компоненты: "+clip(old)+" → "+clip(now)));}
    }
    private static Component resource(ServerLevel level,String kind,CompoundTag tag){
        if(tag.isEmpty())return Component.literal("пусто");
        if(level!=null)try{
            if(kind.equals("item")){var item=NbtSerde.readItemStack(tag,level.registryAccess());if(!item.isEmpty())return item.getHoverName().copy().append(" ×"+item.getCount());}
            else{var fluid=FluidStack.CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE),tag).result().orElse(FluidStack.EMPTY);if(!fluid.isEmpty())return fluid.getHoverName().copy().append(" · "+fluid.getAmount()+" мБ");}
        }catch(RuntimeException ignored){}
        return Component.literal(tag.getString("id")+" · "+(kind.equals("item")?(tag.contains("count")?tag.getInt("count"):1):tag.getInt("amount")));
    }
    private static String clip(String value){return value.length()>512?value.substring(0,512)+"…":value;}
}

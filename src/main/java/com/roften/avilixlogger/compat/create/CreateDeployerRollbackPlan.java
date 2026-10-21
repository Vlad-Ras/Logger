package com.roften.avilixlogger.compat.create;
import net.minecraft.nbt.*;
import com.roften.avilixlogger.core.CreateContraptionKind;
import java.util.*;
/** Detached bounds/identity validation for a complete activation. */
public final class CreateDeployerRollbackPlan {
    public record Undo(CompoundTag before,CompoundTag after){}
    private CreateDeployerRollbackPlan(){}
    public static CreateTransferRollbackPlan prepare(CompoundTag before,CompoundTag after){
        if(before==null||after==null||before.sizeInBytes()+after.sizeInBytes()>8L*1024*1024||!before.getBoolean("Complete")||!after.getBoolean("Complete")||!Set.of(1,2,3,4,5).contains(before.getInt("JointFormat"))||before.getInt("JointFormat")!=after.getInt("JointFormat")||!before.hasUUID("Transfer")||!before.getUUID("Transfer").equals(after.getUUID("Transfer"))||!before.getString("Kind").equals("deployer_action")||!after.getString("Kind").equals("deployer_action"))throw new IllegalStateException("Нет полного совместного снимка активатора");
        if(after.getInt("JointFormat")>=4){
            for(var tag:List.of(before,after))for(var pair:Map.of("World","WorldCount","Targets","TargetCount","Cargo","CargoCount","Uses","UseCount").entrySet())
                if(!tag.contains(pair.getValue(),3)||!tag.contains(pair.getKey(),9)||tag.getInt(pair.getValue())!=CreatePlayerProgress.rows(tag,pair.getKey(),512).size()||tag.getInt(pair.getValue())!=after.getInt(pair.getValue()))throw new IllegalStateException("Манифест активатора потерял участника");
            if(!before.getList("Uses",10).equals(after.getList("Uses",10))||after.getList("Uses",10).size()>64)throw new IllegalStateException("Не согласованы использования предмета");
            for(var raw:after.getList("Uses",10)){var use=(CompoundTag)raw;if(!Set.of("item_use","item_first","block_use","entity_use","attack","harvest","item_finish").contains(use.getString("Action"))||!use.contains("Before",9)||!use.contains("After",9)||use.getList("Before",10).equals(use.getList("After",10)))throw new IllegalStateException("Неполный снимок использования предмета");
                for(String key:List.of("Before","After")){var slots=new HashSet<Integer>();for(var value:CreatePlayerProgress.rows(use,key,41)){var item=(CompoundTag)value;if(!item.contains("Slot",3)||item.getInt("Slot")<0||item.getInt("Slot")>=41||!slots.add(item.getInt("Slot"))||item.getString("id").isBlank()||!item.contains("count",3)||item.getInt("count")<=0)throw new IllegalStateException("Некорректный слот использования предмета");}}
            }
        }
        if(after.getInt("JointFormat")>=5){
            var ids=new HashSet<String>();
            for(var tag:List.of(before,after))if(!tag.contains("ContractCount",3)||!tag.contains("ItemContracts",9)||tag.getInt("ContractCount")!=CreatePlayerProgress.rows(tag,"ItemContracts",64).size())throw new IllegalStateException("Потерян манифест контрактов предметов");
            if(!before.getList("ItemContracts",10).equals(after.getList("ItemContracts",10)))throw new IllegalStateException("Не согласованы контракты предметов");
            for(var raw:after.getList("ItemContracts",10)){var contract=(CompoundTag)raw;CreateDeployerItemContracts.validate(contract);if(!ids.add(contract.getString("Item")))throw new IllegalStateException("Повторный контракт предмета");}
        }
        var contracts=new HashSet<String>();for(var raw:after.getList("ItemContracts",10))contracts.add(((CompoundTag)raw).getString("Item"));
        int selected=before.getCompound("Hand").getInt("Selected");
        validateUsedItems(before.getCompound("Hand").getList("Inventory",10),selected,after.getInt("JointFormat"),contracts);
        for(var raw:after.getList("Uses",10))validateUsedItems(((CompoundTag)raw).getList("Before",10),selected,after.getInt("JointFormat"),contracts);
        var b=before.getCompound("Hand");var a=after.getCompound("Hand");
        if(!b.hasUUID("Construction")||!a.hasUUID("Construction")||!b.getUUID("Construction").equals(a.getUUID("Construction"))||!b.hasUUID("StorageEntity")||!b.getUUID("StorageEntity").equals(a.getUUID("StorageEntity"))||!b.getString("Dimension").equals(a.getString("Dimension"))||a.getString("Dimension").isBlank()||!b.contains("LocalPos",4)||b.getLong("LocalPos")!=a.getLong("LocalPos")||!b.getString("ConstructionKind").equals(a.getString("ConstructionKind"))||CreateContraptionKind.valueOf(a.getString("ConstructionKind"))==CreateContraptionKind.UNKNOWN||!b.getCompound("State").equals(a.getCompound("State"))||!b.getCompound("State").getString("Name").equals("create:deployer")||!b.getCompound("Settings").equals(a.getCompound("Settings")))throw new IllegalStateException("Идентичность или настройки активатора изменены");
        for(var hand:List.of(b,a)){
            if(hand.getInt("Size")!=41||hand.getInt("Selected")<0||hand.getInt("Selected")>=9||!hand.contains("Inventory",9)||!hand.contains("Runtime",10))throw new IllegalStateException("Некорректный инвентарь активатора");
            var runtime=hand.getCompound("Runtime");
            if(after.getInt("JointFormat")>=5)CreateDeployerRuntimeAccess.validatePhysical(runtime.getCompound("Physical"));
            if(!runtime.contains("PlacedTracks",1)||runtime.contains("BreakingPos")!=runtime.contains("BreakingProgress")||runtime.contains("BreakingProgress")&&(!Float.isFinite(runtime.getFloat("BreakingProgress"))||runtime.getFloat("BreakingProgress")<0)||runtime.contains("Timer")&&(runtime.getInt("Timer")<0||runtime.getInt("Timer")>20))throw new IllegalStateException("Некорректное состояние активатора");
            if(runtime.contains("Effects")&&(runtime.getCompound("Effects").getString("id").isBlank()||runtime.getCompound("Effects").getInt("count")<=0))throw new IllegalStateException("Неполный предмет эффекта активатора");
            if(after.getInt("JointFormat")>=3){
                CreateLivingRuntime.validate(runtime.getCompound("Living"));
                CreateCooldownRuntimeAccess.validate(runtime.getCompound("Cooldowns"));
                if(after.getInt("JointFormat")>=4){CreatePlayerProgress.validate(runtime.getCompound("Progress"));CreateScoreRuntime.validate(runtime.getCompound("Scores"));}
            }
            var slots=new HashSet<Integer>();for(var raw:hand.getList("Inventory",10)){var slot=(CompoundTag)raw;if(!slot.contains("Slot",3)||slot.getInt("Slot")<0||slot.getInt("Slot")>=41||!slots.add(slot.getInt("Slot"))||!slot.contains("id",8)||slot.getInt("count")<=0)throw new IllegalStateException("Некорректный или повторный слот руки");}
        }
        var world=pairs(before,after,"World",64);var targets=pairs(before,after,"Targets",32);var positions=new HashSet<String>();var ids=new HashSet<UUID>();
        for(var undo:world){var old=undo.before;var now=undo.after;
            if(!old.contains("Pos",4)||old.getLong("Pos")!=now.getLong("Pos")||!old.getString("Dimension").equals(now.getString("Dimension"))||old.getString("Dimension").isBlank()||!old.contains("State",10)||!now.contains("State",10)||!positions.add(now.getString("Dimension")+":"+now.getLong("Pos")))throw new IllegalStateException("Некорректный адрес цели блока");
            for(var tag:List.of(old,now))if(tag.contains("Runtime",10)||tag.contains("KineticRuntime",10))throw new IllegalStateException("Цель активатора требует структурной транзакции");
        }
        for(var undo:targets){var old=undo.before;var now=undo.after;
            if(!old.hasUUID("Entity")||!old.getUUID("Entity").equals(now.getUUID("Entity"))||!old.getString("Dimension").equals(now.getString("Dimension"))||old.getString("Dimension").isBlank()||!old.getString("Type").equals(now.getString("Type"))||!CreateDeployerTransaction.supportedEntity(old.getString("Type"))||!ids.add(now.getUUID("Entity")))throw new IllegalStateException("Некорректный адрес сущности цели");
            for(var tag:List.of(old,now))if(tag.getBoolean("Exists")&&(!tag.contains("NBT",10)||!tag.getCompound("NBT").hasUUID("UUID")||!tag.getUUID("Entity").equals(tag.getCompound("NBT").getUUID("UUID"))||!tag.getString("Type").equals(tag.getCompound("NBT").getString("id"))||!tag.getCompound("NBT").getList("Passengers",10).isEmpty()))throw new IllegalStateException("Неполный снимок сущности цели");
            if(now.getString("Type").equals("minecraft:player"))for(var tag:List.of(old,now)){if(!tag.getBoolean("Exists"))throw new IllegalStateException("Удалённый игрок не восстанавливается как сущность");CreateHumanRestore.validate(tag.getCompound("Human"));}
            if(old.contains("Terminal")||old.contains("Death"))throw new IllegalStateException("До действия уже записана терминальная цель");
            if(now.getBoolean("Terminal")){
                if(!now.hasUUID("Retained")||after.getInt("JointFormat")<4||!old.getBoolean("Exists")||now.getBoolean("Exists")||!Set.of("minecraft:pig","minecraft:cow","minecraft:sheep","minecraft:chicken").contains(now.getString("Type")))throw new IllegalStateException("Неполный терминальный снимок цели");
                var death=now.getCompound("Death");if(!death.getBoolean("Exists")||!death.equals(addressedDeath(now,death)))throw new IllegalStateException("Адрес умершей цели изменён");
                CreateLivingRuntime.validate(death.getCompound("LivingRuntime"));CreateScoreRuntime.validate(death.getCompound("Scores"));if(death.getCompound("LivingRuntime").getCompound("Persistent").getFloat("Health")>0||!death.getCompound("LivingRuntime").getBoolean("Dead"))throw new IllegalStateException("Терминальный снимок содержит живую цель");
            }else if(now.contains("Death")||old.contains("Death")||old.contains("Terminal"))throw new IllegalStateException("Неожиданный снимок смерти");
            // Formats 1/2 predate health/effect/attribute/combat snapshots. Replaying a
            // living entity from NBT alone can silently leave its transient state behind.
            if(after.getInt("JointFormat")<3&&Set.of("minecraft:player","minecraft:pig","minecraft:cow","minecraft:sheep","minecraft:chicken","minecraft:armor_stand").contains(now.getString("Type")))
                throw new IllegalStateException("У старой записи нет состояния живой цели; откат запрещён");
            if(after.getInt("JointFormat")>=3&&Set.of("minecraft:player","minecraft:pig","minecraft:cow","minecraft:sheep","minecraft:chicken","minecraft:armor_stand").contains(now.getString("Type")))
                for(var tag:List.of(old,now))if(tag.getBoolean("Exists")){CreateLivingRuntime.validate(tag.getCompound("LivingRuntime"));if(after.getInt("JointFormat")>=4&&!tag.getString("Type").equals("minecraft:player"))CreateScoreRuntime.validate(tag.getCompound("Scores"));}
        }
        var creative=new ArrayList<CreateCreativeTransferPlan.IO>();var cargo=cargo(before,after,a,creative);
        if(after.getInt("JointFormat")==2||after.getInt("JointFormat")>=3&&after.contains("CreativeIO")){
            if(!before.contains("CreativeIO",3)||!after.contains("CreativeIO",3)||creative.isEmpty()||before.getInt("CreativeIO")!=after.getInt("CreativeIO")||after.getInt("CreativeIO")!=creative.size())throw new IllegalStateException("Отсутствует часть creative обмена активатора");
        }else if(!creative.isEmpty()||before.contains("CreativeIO")||after.contains("CreativeIO"))throw new IllegalStateException("Creative обмену активатора требуется новый полный формат");
        if(!creative.isEmpty()){
            CreateCreativeTransferPlan.validateHand(b,a,cargo,creative,after.getInt("JointFormat")>=4?after.getList("Uses",10):new ListTag());
        }
        if(b.equals(a)&&world.isEmpty()&&targets.isEmpty()&&cargo.isEmpty()&&creative.isEmpty())throw new IllegalStateException("В операции активатора нет изменений");
        return new CreateTransferRollbackPlan(after.getUUID("Transfer"),cargo,List.of(),List.of(),Set.of(a.getUUID("Construction")),before.copy(),after.copy(),List.of(new Undo(b.copy(),a.copy())),world,targets,List.copyOf(creative));
    }
    private static void validateUsedItems(ListTag inventory,int selected,int format,Set<String> contracts){
        for(var raw:inventory){var item=(CompoundTag)raw;if(item.getInt("Slot")!=selected)continue;String id=item.getString("id");
            if(id.startsWith("minecraft:"))continue;
            if(format>=5? !contracts.contains(id): !Set.of("create:sand_paper","create:red_sand_paper").contains(id))throw new IllegalStateException("Нет контракта модового предмета использования");
        }
    }
    private static CompoundTag addressedDeath(CompoundTag address,CompoundTag death){var expected=death.copy();for(String key:List.of("Entity","Dimension","Type","Pos"))expected.put(key,address.get(key).copy());return expected;}
    private static List<CreateTransferRollbackPlan.CargoUndo> cargo(CompoundTag b,CompoundTag a,CompoundTag hand,List<CreateCreativeTransferPlan.IO> creative){
        var pairs=pairs(b,a,"Cargo",512);var cells=new LinkedHashMap<String,CreateTransferRollbackPlan.CargoUndo>();
        for(var undo:pairs){var old=undo.before();var now=undo.after();var address=now.copy();address.remove("Item");address.remove("Fluid");var previousAddress=old.copy();previousAddress.remove("Item");previousAddress.remove("Fluid");
            if(now.getString("Kind").equals("storage_io")){
                if(!now.hasUUID("Construction")||!hand.getUUID("Construction").equals(now.getUUID("Construction"))||!now.hasUUID("StorageEntity")||!hand.getUUID("StorageEntity").equals(now.getUUID("StorageEntity"))||!hand.getString("Dimension").equals(now.getString("Dimension"))||!hand.getString("ConstructionKind").equals(now.getString("ConstructionKind")))throw new IllegalStateException("Чужой creative-источник активатора");
                creative.add(CreateCreativeTransferPlan.prepare(old,now));continue;
            }
            String kind=now.getString("Kind"),field=kind.equals("item")?"Item":"Fluid";int slot=now.getInt(kind.equals("item")?"Slot":"Tank");
            if(!Set.of("item","fluid").contains(kind)||!address.equals(previousAddress)||!now.hasUUID("Construction")||!hand.getUUID("Construction").equals(now.getUUID("Construction"))||!now.hasUUID("StorageEntity")||!hand.getUUID("StorageEntity").equals(now.getUUID("StorageEntity"))||!hand.getString("Dimension").equals(now.getString("Dimension"))||!hand.getString("ConstructionKind").equals(now.getString("ConstructionKind"))||!now.contains("LocalPos",4)||!now.contains(kind.equals("item")?"Slot":"Tank",3)||slot<0||slot>=512||old.getBoolean("Incomplete")||now.getBoolean("Incomplete"))throw new IllegalStateException("Неполный или чужой груз активатора");
            for(var tag:List.of(old,now)){var stack=tag.getCompound(field);if(!stack.isEmpty()&&(stack.getString("id").isBlank()||stack.getInt(kind.equals("item")?"count":"amount")<=0))throw new IllegalStateException("Некорректный предмет/жидкость активатора");}
            String key=now.getBoolean("BaseCart")+":"+now.getLong("LocalPos")+":"+kind+":"+slot;var prior=cells.get(key);
            if(prior!=null&&!prior.after().equals(old.getCompound(field)))throw new IllegalStateException("Груз активатора изменён между частями операции");
            cells.put(key,new CreateTransferRollbackPlan.CargoUndo(address,prior==null?old.getCompound(field).copy():prior.before(),now.getCompound(field).copy()));
        }
        return cells.values().stream().filter(c->!c.before().equals(c.after())).toList();
    }
    private static List<Undo> pairs(CompoundTag b,CompoundTag a,String key,int max){
        if(!b.contains(key,9)||!a.contains(key,9))throw new IllegalStateException("Не все цели активатора сохранены");var x=b.getList(key,10);var y=a.getList(key,10);if(x.size()!=y.size()||x.size()>max)throw new IllegalStateException("Несогласованный список целей");var out=new ArrayList<Undo>();for(int i=0;i<x.size();i++)out.add(new Undo(x.getCompound(i).copy(),y.getCompound(i).copy()));return List.copyOf(out);
    }
}

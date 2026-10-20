package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Detached plan for one complete exchange, shared by every participant's history. */
public record CreateTransferRollbackPlan(UUID transfer, List<CargoUndo> cargo,
        List<CartAutomationTransferPlan.SlotUndo> external, List<CreateWorldItemTransferPlan.Undo> world,
        Set<UUID> constructions, CompoundTag before, CompoundTag after,
        List<CreateDeployerRollbackPlan.Undo> hands,List<CreateDeployerRollbackPlan.Undo> blocks,List<CreateDeployerRollbackPlan.Undo> targets,List<CreateCreativeTransferPlan.IO> creative,
        List<CreateWorldCreativeTransferPlan.IO> worldCreative) {
    public CreateTransferRollbackPlan(UUID transfer,List<CargoUndo> cargo,List<CartAutomationTransferPlan.SlotUndo> external,List<CreateWorldItemTransferPlan.Undo> world,Set<UUID> constructions,CompoundTag before,CompoundTag after){this(transfer,cargo,external,world,constructions,before,after,List.of(),List.of(),List.of(),List.of(),List.of());}
    public CreateTransferRollbackPlan(UUID transfer,List<CargoUndo> cargo,List<CartAutomationTransferPlan.SlotUndo> external,List<CreateWorldItemTransferPlan.Undo> world,Set<UUID> constructions,CompoundTag before,CompoundTag after,List<CreateDeployerRollbackPlan.Undo> hands,List<CreateDeployerRollbackPlan.Undo> blocks,List<CreateDeployerRollbackPlan.Undo> targets){this(transfer,cargo,external,world,constructions,before,after,hands,blocks,targets,List.of(),List.of());}
    public CreateTransferRollbackPlan(UUID transfer,List<CargoUndo> cargo,List<CartAutomationTransferPlan.SlotUndo> external,List<CreateWorldItemTransferPlan.Undo> world,Set<UUID> constructions,CompoundTag before,CompoundTag after,List<CreateDeployerRollbackPlan.Undo> hands,List<CreateDeployerRollbackPlan.Undo> blocks,List<CreateDeployerRollbackPlan.Undo> targets,List<CreateCreativeTransferPlan.IO> creative){this(transfer,cargo,external,world,constructions,before,after,hands,blocks,targets,creative,List.of());}
    public record CargoUndo(CompoundTag address, CompoundTag before, CompoundTag after) {}
    private record Cell(UUID construction, UUID storage, boolean base, long pos, String kind, int slot) {}

    public static CreateTransferRollbackPlan prepare(CompoundTag before, CompoundTag after) {
        if(after!=null&&after.getString("Kind").equals("deployer_action"))return CreateDeployerRollbackPlan.prepare(before,after);
        if(before==null||after==null||before.sizeInBytes()+after.sizeInBytes()>16L*1024*1024
                ||!before.hasUUID("Transfer")||!after.hasUUID("Transfer")||!before.getUUID("Transfer").equals(after.getUUID("Transfer")))
            throw new IllegalStateException("Неполный или слишком большой снимок передачи");
        var b=before.getList("Cargo",10);var a=after.getList("Cargo",10);
        if(!before.contains("Cargo",9)||!after.contains("Cargo",9)||b.size()!=a.size()||a.isEmpty()||a.size()>512)
            throw new IllegalStateException("В этой записи нет полного снимка груза всех участников");
        Set<UUID> constructions=new LinkedHashSet<>();
        var kinds=new HashMap<UUID,String>();
        var storageOwners=new HashMap<UUID,CompoundTag>();
        var cells=new LinkedHashMap<Cell,CargoUndo>();var creative=new ArrayList<CreateCreativeTransferPlan.IO>();
        var validator=new CartAutomationTransferPlan();validator.manifest(before,after);
        for(int i=0;i<a.size();i++){
            var old=b.getCompound(i);var now=a.getCompound(i);String kind=now.getString("Kind");
            if(!Set.of("item","fluid","storage_io").contains(kind)||!old.hasUUID("Construction")||!now.hasUUID("Construction")
                    ||!old.hasUUID("StorageEntity")||!now.hasUUID("StorageEntity")||old.getInt("TransferIndex")!=i||now.getInt("TransferIndex")!=i
                    ||!old.getUUID("Construction").equals(now.getUUID("Construction"))||!old.getUUID("StorageEntity").equals(now.getUUID("StorageEntity"))
                    ||!old.getString("Dimension").equals(now.getString("Dimension"))||now.getString("Dimension").isBlank()
                    ||!old.getString("ConstructionKind").equals(now.getString("ConstructionKind"))
                    ||CreateContraptionKind.valueOf(now.getString("ConstructionKind"))==CreateContraptionKind.UNKNOWN
                    ||old.hasUUID("Carriage")!=now.hasUUID("Carriage")||old.hasUUID("Carriage")&&!old.getUUID("Carriage").equals(now.getUUID("Carriage")))
                throw new IllegalStateException("Адрес или вид участника передачи изменён");
            var id=now.getUUID("Construction");constructions.add(id);
            var previousKind=kinds.putIfAbsent(id,now.getString("ConstructionKind"));
            if(previousKind!=null&&!previousKind.equals(now.getString("ConstructionKind")))throw new IllegalStateException("Один ID имеет разные виды конструкции");
            var owner=new CompoundTag();owner.putUUID("Construction",id);owner.putString("ConstructionKind",now.getString("ConstructionKind"));owner.putString("Dimension",now.getString("Dimension"));
            if(now.hasUUID("Carriage"))owner.putUUID("Carriage",now.getUUID("Carriage"));
            var previousOwner=storageOwners.putIfAbsent(now.getUUID("StorageEntity"),owner);
            if(previousOwner!=null&&!previousOwner.equals(owner))throw new IllegalStateException("Сущность хранилища принадлежит разным участникам");
            if(constructions.size()>32)throw new IllegalStateException("Передача затронула больше 32 конструкций");
            validator.cargo(old,now);
            if(kind.equals("storage_io")){creative.add(CreateCreativeTransferPlan.prepare(old,now));continue;}
            String field=kind.equals("item")?"Item":"Fluid";int slot=now.getInt(kind.equals("item")?"Slot":"Tank");
            if(slot<0||slot>=512)throw new IllegalStateException("Некорректный слот груза");
            var key=new Cell(id,now.getUUID("StorageEntity"),now.getBoolean("BaseCart"),now.getLong("LocalPos"),kind,slot);
            var previous=cells.get(key);
            if(previous!=null&&!previous.after.equals(old.getCompound(field)))throw new IllegalStateException("Груз перемещён между частями передачи");
            var address=now.copy();address.remove("Item");address.remove("Fluid");
            cells.put(key,new CargoUndo(address,previous==null?old.getCompound(field).copy():previous.before,now.getCompound(field).copy()));
        }
        var external=validator.finish(constructions);
        return new CreateTransferRollbackPlan(after.getUUID("Transfer"),cells.values().stream().filter(c->!c.before.equals(c.after)).toList(),
                external,validator.worldItems(),Set.copyOf(constructions),before.copy(),after.copy(),List.of(),List.of(),List.of(),List.copyOf(creative),validator.worldCreative());
    }
}

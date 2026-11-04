package com.roften.avilixlogger.compat.create;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import java.util.*;
/** Detached complete native disassembly, including controllers and final world inventories. */
public record CreateStructuralRollbackPlan(UUID operation,List<Body> bodies,List<World> world,List<CompoundTag> effects,Set<UUID> constructions,CompoundTag before,CompoundTag after){
    public record Body(CompoundTag before,CompoundTag after){}
    public record World(CompoundTag before,CompoundTag after){}
    private record Address(String dim,long pos){}
    public static CreateStructuralRollbackPlan prepare(CompoundTag before,CompoundTag after){
        if(before==null||after==null||before.sizeInBytes()+after.sizeInBytes()>128L*1024*1024)throw new IllegalStateException("Нет полного снимка разборки");
        for(var tag:List.of(before,after))if(tag.getInt("Format")!=3||!tag.getString("Kind").equals("structural")||!tag.getBoolean("Complete")||!tag.hasUUID("Operation")
                ||!tag.contains("Bodies",9)||!tag.contains("World",9)||!tag.contains("Effects",9))throw new IllegalStateException("Разборка не захвачена полностью");
        for(var tag:List.of(before,after))for(var name:List.of("Bodies","World","Effects")){var list=(ListTag)tag.get(name);if(!list.isEmpty()&&list.getElementType()!=10)throw new IllegalStateException("Повреждён список снимка: "+name);}
        if(!before.getUUID("Operation").equals(after.getUUID("Operation")))throw new IllegalStateException("Несвязанные снимки разборки");
        var oldBodies=before.getList("Bodies",10);var newBodies=after.getList("Bodies",10);var bodies=new ArrayList<Body>();var entities=new HashSet<UUID>();var roots=new HashSet<UUID>();
        if(oldBodies.isEmpty()||oldBodies.size()>64||oldBodies.size()!=newBodies.size())throw new IllegalStateException("Не все связанные конструкции сохранены");
        for(int i=0;i<oldBodies.size();i++){
            var b=oldBodies.getCompound(i);var a=newBodies.getCompound(i);
            if(!b.hasUUID("Construction")||!b.hasUUID("StorageEntity")||!a.hasUUID("Construction")||!a.hasUUID("StorageEntity")||!b.getUUID("Construction").equals(a.getUUID("Construction"))
                    ||!b.getUUID("StorageEntity").equals(a.getUUID("StorageEntity"))||!b.getString("Dimension").equals(a.getString("Dimension"))||b.getString("Dimension").isBlank()
                    ||!b.getString("ConstructionKind").equals(a.getString("ConstructionKind"))||!b.getString("Form").equals("entity")||!a.getString("Form").equals("blocks")||!entities.add(b.getUUID("StorageEntity")))throw new IllegalStateException("Форма или адрес конструкции изменены");
            var kind=CreateContraptionKind.valueOf(b.getString("ConstructionKind"));var entity=b.getCompound("Entity");var payload=entity.getCompound("Contraption");
            if(kind==CreateContraptionKind.UNKNOWN||kind==CreateContraptionKind.TRAIN&&!before.contains("Train",10)||CreateContraptionKind.fromType(payload.getString("Type"))!=kind||!entity.hasUUID("UUID")||!entity.getUUID("UUID").equals(b.getUUID("StorageEntity"))||payload.getCompound("Blocks").getList("BlockList",10).isEmpty())throw new IllegalStateException("Нет проверенного payload конструкции; поезд требует транзакции своего реестра");
            roots.add(b.getUUID("Construction"));bodies.add(new Body(b.copy(),a.copy()));
        }
        var bases=new HashMap<UUID,CompoundTag>();
        if(before.contains("Carts")||after.contains("Carts")){for(var envelope:List.of(before,after))if(!(envelope.get("Carts") instanceof ListTag list)||list.isEmpty()||list.getElementType()!=10)throw new IllegalStateException("Нет полного списка баз сцепки");if(before.contains("Train")||after.contains("Train"))throw new IllegalStateException("Смешаны разные реестры транспорта");for(var raw:before.getList("Carts",10)){var tag=(CompoundTag)raw;if(!tag.hasUUID("CartEntity")||bases.put(tag.getUUID("CartEntity"),tag)!=null)throw new IllegalStateException("Повторная база сцепки");}roots.addAll(CreateCartRegistry.validate(before.getList("Carts",10),after.getList("Carts",10),bodies));}
        var parents=new HashMap<UUID,UUID>();var passengers=new HashMap<UUID,Set<UUID>>();
        for(var body:bodies){var seen=new HashSet<String>();for(var raw:body.before.getList("Links",10)){
            var link=(CompoundTag)raw;String relation=link.getString("Relation");
            if(link.hasUUID("Entity")&&!bases.isEmpty()&&Set.of("vehicle","coupled_main","coupled_connected").contains(relation)){
                if(!seen.add(relation+link.getUUID("Entity")))throw new IllegalStateException("Повторная связь базы");var base=bases.values().stream().filter(tag->tag.hasUUID("ConstructionEntity")&&tag.getUUID("ConstructionEntity").equals(body.before.getUUID("StorageEntity"))).findFirst().orElseThrow(()->new IllegalStateException("Нет базы тела"));
                if(relation.equals("vehicle")){if(!link.getUUID("Entity").equals(base.getUUID("CartEntity"))||!link.getString("Dimension").equals(base.getString("Dimension"))||!link.getString("EntityType").equals(base.getString("CartType")))throw new IllegalStateException("Несвязанная базовая вагонетка");}
                else {var edge=base.getCompound("Native").getCompound(relation.equals("coupled_main")?"MainCoupling":"ConnectedCoupling");if(!bases.containsKey(link.getUUID("Entity"))||!edge.hasUUID(relation.equals("coupled_main")?"Connected":"Main")||!link.getUUID("Entity").equals(edge.getUUID(relation.equals("coupled_main")?"Connected":"Main"))||!link.contains("Length",5)||link.getFloat("Length")!=edge.getFloat("Length")||link.getBoolean("ThroughContraption")!=edge.getBoolean("Contraption"))throw new IllegalStateException("Тело не совпадает с графом сцепки");}continue;
            }
            if(!link.hasUUID("Entity")||!entities.contains(link.getUUID("Entity"))||!Set.of("vehicle","passenger","stabilized").contains(relation)||!seen.add(relation+link.getUUID("Entity")))throw new IllegalStateException("Внешний или повторный участник связи");
            UUID id=body.before.getUUID("StorageEntity"),peer=link.getUUID("Entity");
            if(relation.equals("vehicle")&&parents.put(id,peer)!=null)throw new IllegalStateException("У конструкции несколько родителей");
            if(relation.equals("passenger"))passengers.computeIfAbsent(id,ignored->new HashSet<>()).add(peer);
        }}
        for(var pair:parents.entrySet())if(!passengers.getOrDefault(pair.getValue(),Set.of()).contains(pair.getKey()))throw new IllegalStateException("Обратная связь пассажира отсутствует");
        for(var pair:passengers.entrySet())for(var child:pair.getValue())if(!pair.getKey().equals(parents.get(child)))throw new IllegalStateException("Обратная связь транспорта отсутствует");
        for(UUID id:entities){var visited=new HashSet<UUID>();for(UUID current=id;current!=null;current=parents.get(current))if(!visited.add(current))throw new IllegalStateException("Цикл вложенных конструкций");}
        var oldWorld=before.getList("World",10);var newWorld=after.getList("World",10);var world=new ArrayList<World>();var addresses=new HashSet<Address>();int controllers=0;
        if(oldWorld.isEmpty()||oldWorld.size()>8192||oldWorld.size()!=newWorld.size())throw new IllegalStateException("Не все изменения мира сохранены");
        for(int i=0;i<oldWorld.size();i++){
            var b=oldWorld.getCompound(i);var a=newWorld.getCompound(i);
            if(!b.contains("Pos",4)||!a.contains("Pos",4)||b.getLong("Pos")!=a.getLong("Pos")||b.getString("Dimension").isBlank()||!b.getString("Dimension").equals(a.getString("Dimension"))
                    ||!b.contains("State",10)||!a.contains("State",10)||b.getBoolean("Controller")!=a.getBoolean("Controller")||!addresses.add(new Address(a.getString("Dimension"),a.getLong("Pos"))))throw new IllegalStateException("Некорректный или повторный адрес блока");
            if(b.getBoolean("ControllerRemoved")!=a.getBoolean("ControllerRemoved"))throw new IllegalStateException("Несогласованный снимок удаления контроллера");
            if(b.getBoolean("ControllerRemoved")&&(!b.getBoolean("Controller")||!b.contains("Runtime",10)||a.contains("Runtime",10)||b.getCompound("State").getString("Name").equals(a.getCompound("State").getString("Name"))))throw new IllegalStateException("Некорректное удаление контроллера");
            if(b.getBoolean("Controller")){controllers++;if(!b.contains("BE",10)||(!b.getBoolean("ControllerRemoved")&&(!a.contains("BE",10)||!b.getCompound("BE").getString("id").equals(a.getCompound("BE").getString("id")))))throw new IllegalStateException("Контроллер отсутствует или заменён");}
            for(var snapshot:List.of(b,a))if(snapshot.getCompound("Runtime").getBoolean("Removing"))throw new IllegalStateException("Удаляемый или зеркальный контроллер требует отдельной транзакции");
            if(b.contains("ElevatorColumn")||a.contains("ElevatorColumn")){
                if(!b.getBoolean("Controller")||!b.getCompound("BE").getString("id").equals("create:elevator_pulley")||!CreateElevatorRegistry.validate(b.getCompound("ElevatorColumn")).equals(CreateElevatorRegistry.validate(a.getCompound("ElevatorColumn"))))throw new IllegalStateException("Некорректный адрес реестра лифта");
            }
            world.add(new World(b.copy(),a.copy()));
        }
        for(var body:bodies)if(body.before.getCompound("Entity").getCompound("Contraption").getString("Type").equals("create:elevator")){
            var payload=body.before.getCompound("Entity").getCompound("Contraption");
            var anchor=NbtUtils.readBlockPos(payload,"Anchor").orElseThrow(()->new IllegalStateException("Нет якоря лифта"));
            var coords=com.simibubi.create.content.contraptions.elevator.ElevatorColumn.ColumnCoords.read(payload.getCompound("Column")).relative(anchor);
            var matching=world.stream().filter(e->e.before.getBoolean("Controller")&&e.before.getString("Dimension").equals(body.before.getString("Dimension"))&&e.before.contains("ElevatorColumn",10)&&coords.equals(CreateElevatorRegistry.validate(e.before.getCompound("ElevatorColumn")))).toList();
            if(matching.size()!=1)throw new IllegalStateException("Нет единственного реестра колонки лифта");
            var endpoint=matching.getFirst();var b=endpoint.before.getCompound("ElevatorColumn");var a=endpoint.after.getCompound("ElevatorColumn");
            if(!coords.equals(CreateElevatorRegistry.validate(a))||!b.getBoolean("Exists")||!b.getCompound("State").getBoolean("Active")||a.getCompound("State").getBoolean("Active"))throw new IllegalStateException("Состояние колонки не соответствует разборке лифта");
            for(int y:b.getCompound("State").getIntArray("Contacts"))if(world.stream().noneMatch(e->e.before.getString("Dimension").equals(body.before.getString("Dimension"))&&BlockPos.of(e.before.getLong("Pos")).equals(new BlockPos(coords.x(),y,coords.z()))))throw new IllegalStateException("Контакт колонки лифта не сохранён");
        }
        var capturedNetworks=new HashSet<String>();
        for(var endpoint:world){var beforeNetworks=endpoint.before.getList("KineticNetworks",10);var afterNetworks=endpoint.after.getList("KineticNetworks",10);
            if(endpoint.before.contains("KineticNetworks")!=endpoint.after.contains("KineticNetworks")||beforeNetworks.size()!=afterNetworks.size())throw new IllegalStateException("Неполный снимок кинетических реестров");
            for(int i=0;i<beforeNetworks.size();i++){var b=beforeNetworks.getCompound(i);var a=afterNetworks.getCompound(i);long id=CreateKineticRegistry.validate(b);
                if(id!=CreateKineticRegistry.validate(a)||!capturedNetworks.add(endpoint.before.getString("Dimension")+":"+id))throw new IllegalStateException("Повторный или заменённый кинетический реестр");
                for(var tag:List.of(b,a))for(var raw:tag.getCompound("State").getList("Members",10)){var member=(CompoundTag)raw;if(world.stream().noneMatch(e->e.before.getLong("Pos")==member.getLong("Pos")&&e.before.getString("Dimension").equals(endpoint.before.getString("Dimension"))))throw new IllegalStateException("Участник кинетической сети не сохранён");}
            }
        }
        if(before.contains("Train")||after.contains("Train")){if(!before.contains("Train",10)||!after.contains("Train",10))throw new IllegalStateException("Неполный снимок реестра поезда");CreateTrainRegistry.validate(before.getCompound("Train"),after.getCompound("Train"),bodies,world);}
        else if(controllers==0&&bases.isEmpty())throw new IllegalStateException("Нет контроллера разборки");
        // Mirror edges must be local, bidirectional, complete, type-preserving and acyclic.
        for(boolean previous:List.of(true,false)){
            var graph=new HashMap<Address,Address>();
            for(var endpoint:world){var tag=previous?endpoint.before: endpoint.after;var be=tag.getCompound("BE");
                if(!be.contains("MirrorParent")&&be.getList("MirrorChildren",10).isEmpty())continue;
                if(!tag.getBoolean("Controller")||!Set.of("create:rope_pulley","create:elevator_pulley").contains(be.getString("id")))throw new IllegalStateException("Непроверенный зеркальный контроллер");
                var address=new Address(tag.getString("Dimension"),tag.getLong("Pos"));
                if(be.contains("MirrorParent")){
                    var parentPos=NbtUtils.readBlockPos(be,"MirrorParent").orElseThrow(()->new IllegalStateException("Повреждён MirrorParent"));
                    var parent=world.stream().map(e->previous?e.before:e.after).filter(t->t.getBoolean("Controller")&&t.getString("Dimension").equals(address.dim)&&t.getLong("Pos")==parentPos.asLong()).findFirst().orElseThrow(()->new IllegalStateException("Родительский шкив не сохранён"));
                    if(!be.getString("id").equals(parent.getCompound("BE").getString("id"))||parent.getCompound("BE").getList("MirrorChildren",10).stream().noneMatch(raw->NbtUtils.readBlockPos((CompoundTag)raw,"Pos").map(p->p.asLong()==address.pos).orElse(false)))throw new IllegalStateException("Обратная связь шкива отсутствует");
                    graph.put(address,new Address(address.dim,parentPos.asLong()));
                }
                var children=new HashSet<Long>();
                for(var raw:be.getList("MirrorChildren",10)){
                    var pos=NbtUtils.readBlockPos((CompoundTag)raw,"Pos").orElseThrow(()->new IllegalStateException("Повреждён ведомый шкив"));
                    if(!children.add(pos.asLong()))throw new IllegalStateException("Повторный ведомый шкив");
                    var child=world.stream().map(e->previous?e.before:e.after).filter(t->t.getBoolean("Controller")&&t.getString("Dimension").equals(address.dim)&&t.getLong("Pos")==pos.asLong()).findFirst().orElseThrow(()->new IllegalStateException("Ведомый шкив не сохранён"));
                    if(!be.getString("id").equals(child.getCompound("BE").getString("id"))||!NbtUtils.readBlockPos(child.getCompound("BE"),"MirrorParent").map(p->p.asLong()==address.pos).orElse(false))throw new IllegalStateException("Родитель ведомого шкива не совпадает");
                }
            }
            for(var start:graph.keySet()){var visited=new HashSet<Address>();for(var address=start;address!=null;address=graph.get(address))if(!visited.add(address))throw new IllegalStateException("Цикл зеркальных шкивов");}
        }
        for(var body:bodies){var entity=body.before.getCompound("Entity");
            if(entity.getString("id").equals("create:controlled_contraption")){
                var position=entity.getList("Pos",6);if(position.size()!=3)throw new IllegalStateException("Нет позиции контролируемой конструкции");
                var relative=NbtUtils.readBlockPos(entity,"ControllerRelative").orElseThrow(()->new IllegalStateException("Нет адреса контроллера"));
                var pos=relative.offset(BlockPos.containing(position.getDouble(0),position.getDouble(1),position.getDouble(2)));
                if(world.stream().noneMatch(endpoint->endpoint.before.getBoolean("Controller")&&endpoint.before.getString("Dimension").equals(body.before.getString("Dimension"))&&endpoint.before.getLong("Pos")==pos.asLong()))throw new IllegalStateException("Контроллер участника не сохранён");
            }
        }
        if(!before.getList("Effects",10).isEmpty()||after.getList("Effects",10).size()>512)throw new IllegalStateException("Не все эффекты разборки сохранены");
        var effects=new ArrayList<CompoundTag>();var effectIds=new HashSet<UUID>();
        for(var raw:after.getList("Effects",10)){
            var effect=(CompoundTag)raw;var nbt=effect.getCompound("NBT");
            if(!effect.hasUUID("Entity")||!effectIds.add(effect.getUUID("Entity"))||entities.contains(effect.getUUID("Entity"))||effect.getString("Dimension").isBlank()||!nbt.hasUUID("UUID")||!effect.getUUID("Entity").equals(nbt.getUUID("UUID"))
                    ||!Set.of("minecraft:item","create:super_glue").contains(effect.getString("EntityType"))||!effect.getString("EntityType").equals(nbt.getString("id"))||!nbt.getList("Passengers",10).isEmpty())throw new IllegalStateException("Нет обратимого снимка дропа/клея");
            effects.add(effect.copy());
        }
        return new CreateStructuralRollbackPlan(after.getUUID("Operation"),List.copyOf(bodies),List.copyOf(world),List.copyOf(effects),Set.copyOf(roots),before.copy(),after.copy());
    }
}

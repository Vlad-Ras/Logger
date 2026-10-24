package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.*;
import com.simibubi.create.content.trains.graph.*;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.lang.ref.WeakReference;
import java.util.*;

/** The train registry is restored together with bodies/world, never by an isolated entity load. */
public final class CreateTrainRegistry {
    public static final String RAILWAY_MUTATION="create/railway/world-mutation";
    private CreateTrainRegistry(){}
    private static String difference(Tag actual,Tag expected,String path){
        if(Objects.equals(actual,expected))return path;
        if(actual instanceof CompoundTag a&&expected instanceof CompoundTag b){
            var keys=new TreeSet<String>();keys.addAll(a.getAllKeys());keys.addAll(b.getAllKeys());
            for(var key:keys)if(!Objects.equals(a.get(key),b.get(key)))return difference(a.get(key),b.get(key),path+"."+key);
        }else if(actual instanceof ListTag a&&expected instanceof ListTag b){
            if(a.size()!=b.size())return path+".size";
            for(int i=0;i<a.size();i++)if(!a.get(i).equals(b.get(i)))return difference(a.get(i),b.get(i),path+"["+i+"]");
        }
        return path;
    }

    public static String key(UUID id){return "create/train/"+id;}
    public static boolean locked(UUID id){return CartRestoreLocks.resourceLocked(key(id));}
    public static boolean stationLocked(UUID id){return CartRestoreLocks.resourceLocked("create/station/"+id);}
    public static CompoundTag snapshot(Train train,ServerLevel level){
        if(Create.RAILWAYS.trains.get(train.id)!=train)throw new IllegalStateException("Поезд отсутствует в RailwayManager");
        if(train.carriages.isEmpty()||train.carriages.size()>64)throw new IllegalStateException("Некорректный состав поезда");
        var tag=new CompoundTag();tag.putUUID("Id",train.id);tag.putBoolean("Registered",true);var manager=(CreateRailwayAccess)Create.RAILWAYS;
        tag.putInt("Moving",manager.avilixlogger$moving(train.id));tag.putInt("Waiting",manager.avilixlogger$waiting(train.id));
        var palette=new DimensionPalette();tag.put("Native",write(train,palette,level.registryAccess()));tag.put("Runtime",((CreateTrainRuntimeAccess)train).avilixlogger$trainRuntime(level.registryAccess()));tag.put("NavigationRuntime",((CreateNavigationAccess)train.navigation).avilixlogger$navigation(palette));palette.write(tag);
        tag.put("CarriageRuntime",carriageRuntime(train));
        if(train.graph!=null){if(train.graph.getNodes().size()>8192)throw new IllegalStateException("Путевой граф превышает 8192 узла");var graph=new CompoundTag();var dimensions=new DimensionPalette();graph.put("Native",train.graph.write(level.registryAccess(),dimensions));dimensions.write(graph);tag.put("Graph",graph);}
        var station=train.getCurrentStation();if(train.currentStation!=null&&station==null)throw new IllegalStateException("Станция поезда отсутствует");
        if(station!=null){var point=new CompoundTag();point.putUUID("Id",station.id);point.putString("Dimension",station.getBlockEntityDimension().location().toString());point.putLong("Pos",station.getBlockEntityPos().asLong());nearest(point,station);tag.put("Station",point);}
        var groups=new ListTag();var ids=new TreeSet<UUID>();ids.addAll(train.occupiedSignalBlocks.keySet());ids.addAll(train.reservedSignalBlocks);
        for(var id:ids){var group=Create.RAILWAYS.signalEdgeGroups.get(id);if(group==null)throw new IllegalStateException("Сигнальный блок поезда отсутствует");var entry=new CompoundTag();entry.putUUID("Id",id);var state=group.write();entry.put("Native",state);entry.putBoolean("Present",group.trains.stream().anyMatch(t->t.id.equals(train.id)));if(train.reservedSignalBlocks.contains(id)&&group.reserved!=null)entry.putUUID("Reserved",group.reserved.id);groups.add(entry);}tag.put("Signals",groups);return tag;
    }
    private static ListTag carriageRuntime(Train train){
        var extras=new ListTag();
        for(var carriage:train.carriages){var state=new CompoundTag();state.putBoolean("Blocked",carriage.blocked);state.putInt("CargoVersion",carriage.storage.getVersion());var points=new ListTag();for(var bogey:carriage.bogeys)if(bogey!=null)for(var point:List.of(bogey.leading(),bogey.trailing())){var value=new CompoundTag();value.putBoolean("Blocked",point.blocked);value.putDouble("Position",point.position);value.putBoolean("UpsideDown",point.upsideDown);points.add(value);}state.put("Points",points);extras.add(state);}return extras;
    }
    private static CompoundTag write(Train train,DimensionPalette palette,net.minecraft.core.HolderLookup.Provider registries){
        var angles=new IdentityHashMap<CreateOrientationAccess,Boolean>();for(var carriage:train.carriages)carriage.forEachPresentEntity(entity->{if(entity instanceof CreateOrientationAccess access)angles.put(access,access.avilixlogger$forceAngle());});
        try{var result=train.write(palette,registries);orderRepresentations(result);return result;}finally{angles.forEach(CreateOrientationAccess::avilixlogger$forceAngle);}
    }
    /** Create serializes a dimension-keyed HashMap as a list; its iteration order has no meaning. */
    static void orderRepresentations(CompoundTag nativeTrain){
        for(var raw:nativeTrain.getList("Carriages",Tag.TAG_COMPOUND)){
            var carriage=(CompoundTag)raw;var positions=carriage.getList("EntityPositioning",Tag.TAG_COMPOUND);
            var sorted=new ArrayList<Tag>(positions);sorted.sort(Comparator.comparingInt(t->((CompoundTag)t).getInt("Dim")));
            var result=new ListTag();result.addAll(sorted);carriage.put("EntityPositioning",result);
        }
    }
    private static void nearest(CompoundTag tag,GlobalStation station){tag.remove("Nearest");var train=station.nearestTrain.get();if(train!=null)tag.putUUID("Nearest",train.id);}
    public static CompoundTag after(CompoundTag before,Train train){
        if(Create.RAILWAYS.trains.get(train.id)==train){var level=(ServerLevel)train.carriages.getFirst().anyAvailableEntity().level();return snapshot(train,level);}
        var tag=before.copy();tag.putBoolean("Registered",false);tag.putInt("Moving",-1);tag.putInt("Waiting",-1);for(String field:List.of("Native","Runtime","NavigationRuntime","CarriageRuntime","DimensionPalette"))tag.remove(field);
        if(tag.contains("Station",10)){var station=train.getCurrentStation();if(station==null)throw new IllegalStateException("Станция исчезла при разборке");nearest(tag.getCompound("Station"),station);}
        // removeTrain leaves transient group membership until the next railway tick.
        // Record what exists now; compensation retains the references present at undo start.
        for(var raw:tag.getList("Signals",10)){var value=(CompoundTag)raw;var group=Create.RAILWAYS.signalEdgeGroups.get(value.getUUID("Id"));if(group==null)throw new IllegalStateException("Сигнальная группа исчезла при разборке");value.put("Native",group.write());value.putBoolean("Present",group.trains.stream().anyMatch(t->t.id.equals(train.id)));value.remove("Reserved");if(train.reservedSignalBlocks.contains(group.id)&&group.reserved!=null)value.putUUID("Reserved",group.reserved.id);}return tag;
    }
    public static CompoundTag payload(CompoundTag envelope,CompoundTag body){
        var entity=body.getCompound("Entity");if(!envelope.contains("Train",10)||!entity.getString("id").equals("create:carriage_contraption"))return entity;
        var result=entity.copy();int index=entity.getInt("CarriageIndex");var carriage=envelope.getCompound("Train").getCompound("Native").getList("Carriages",10).getCompound(index);
        var positioning=carriage.getList("EntityPositioning",10);if(positioning.size()!=1)throw new IllegalStateException("Разборка портального вагона не является обратимой операцией");
        var storage=positioning.getCompound(0);for(String field:List.of("items","fluids"))if(storage.contains(field,9))result.getCompound("Contraption").put(field,storage.get(field).copy());return result;
    }
    private static CompoundTag canonicalEntity(CompoundTag original){var tag=original.copy();tag.remove("Passengers");tag.getCompound("Contraption").remove("Passengers");return tag;}
    public static void validate(CompoundTag before,CompoundTag after,List<CreateStructuralRollbackPlan.Body> bodies,List<CreateStructuralRollbackPlan.World> world){
        if(!before.hasUUID("Id")||!after.hasUUID("Id")||!before.getUUID("Id").equals(after.getUUID("Id"))||!before.getBoolean("Registered")||after.getBoolean("Registered")||after.contains("Native")||after.getInt("Moving")!=-1||after.getInt("Waiting")!=-1)throw new IllegalStateException("Нет полного перехода RailwayManager");
        var nativeTag=before.getCompound("Native");var id=before.getUUID("Id");if(!nativeTag.hasUUID("Id")||!id.equals(nativeTag.getUUID("Id"))||!before.contains("Runtime",10)||!before.contains("NavigationRuntime",10)||!before.contains("DimensionPalette",9)||before.getList("DimensionPalette",10).size()>16||before.getList("DimensionPalette",10).isEmpty())throw new IllegalStateException("Нет полного NBT поезда");
        if((before.getInt("Moving")>=0)==(before.getInt("Waiting")>=0)||before.getCompound("Runtime").getBoolean("Invalid"))throw new IllegalStateException("Поезд отсутствует в единственной рабочей очереди");
        var carriages=nativeTag.getList("Carriages",10);if(carriages.isEmpty()||carriages.size()>64||before.getList("CarriageRuntime",10).size()!=carriages.size()||nativeTag.getIntArray("CarriageSpacing").length!=carriages.size()-1)throw new IllegalStateException("Состав поезда неполный");
        for(String field:List.of("Signals","CarriageRuntime","DimensionPalette"))compoundList(before,field);compoundList(after,"Signals");
        var signalIds=new HashSet<UUID>();for(String field:List.of("SignalBlocks","ReservedSignalBlocks"))for(var raw:nativeTag.getList(field,10)){var value=(CompoundTag)raw;if(!value.hasUUID("Id"))throw new IllegalStateException("Нет UUID сигнальной группы");signalIds.add(value.getUUID("Id"));}
        var beforeSignals=before.getList("Signals",10);var afterSignals=after.getList("Signals",10);if(beforeSignals.size()!=signalIds.size()||afterSignals.size()!=beforeSignals.size())throw new IllegalStateException("Не все сигнальные группы сохранены");
        for(int i=0;i<beforeSignals.size();i++){var saved=beforeSignals.getCompound(i);var next=afterSignals.getCompound(i);if(!saved.hasUUID("Id")||!next.hasUUID("Id")||!saved.getUUID("Id").equals(next.getUUID("Id"))||!signalIds.remove(saved.getUUID("Id"))||!saved.contains("Native",10)||!saved.getCompound("Native").equals(next.getCompound("Native"))||!saved.contains("Present",1)||!next.contains("Present",1))throw new IllegalStateException("Повреждён снимок сигнальной группы");}
        if(nativeTag.hasUUID("Station")!=before.contains("Station",10))throw new IllegalStateException("Не захвачена станция поезда");
        var seen=new HashSet<Integer>();var carriageIds=new HashSet<UUID>();
        for(var body:bodies){if(!body.before().getString("ConstructionKind").equals("TRAIN"))continue;var entity=body.before().getCompound("Entity");int index=entity.getInt("CarriageIndex");
            if(!entity.hasUUID("TrainId")||!id.equals(entity.getUUID("TrainId"))||!id.equals(body.before().getUUID("Construction"))||index<0||index>=carriages.size()||!seen.add(index)||body.before().getBoolean("Portal")||!body.before().hasUUID("Carriage")||!carriageIds.add(body.before().getUUID("Carriage")))throw new IllegalStateException("Несвязанный, повторный или портальный вагон");
            var orientation=body.before().getCompound("Orientation");if(!orientation.contains("ForceAngle",1)||!orientation.contains("NonDamageTicks",3))throw new IllegalStateException("Нет состояния поворота вагона");for(String field:List.of("Yaw","PrevYaw","TargetYaw","Pitch","PrevPitch"))if(!orientation.contains(field,5)||!Float.isFinite(orientation.getFloat(field)))throw new IllegalStateException("Неполный поворот вагона");
            var bounds=body.before().getList("Bounds",6);if(bounds.size()!=6)throw new IllegalStateException("Нет границ представления вагона");for(int k=0;k<6;k++)if(!Double.isFinite(bounds.getDouble(k)))throw new IllegalStateException("Повреждены границы вагона");for(int k=0;k<3;k++)if(bounds.getDouble(k)>bounds.getDouble(k+3)||bounds.getDouble(k+3)-bounds.getDouble(k)>512)throw new IllegalStateException("Непроверенный размер вагона");
            var carriage=carriages.getCompound(index);var positioning=carriage.getList("EntityPositioning",10);
            if(!carriage.hasUUID("AvilixCarriageId")||!carriage.getUUID("AvilixCarriageId").equals(body.before().getUUID("Carriage"))||!carriage.getCompound("Entity").equals(canonicalEntity(entity))||positioning.size()!=1||!carriage.getCompound("Passengers").isEmpty())throw new IllegalStateException("Payload вагона или пассажиры не захвачены полностью");
            int dim=positioning.getCompound(0).getInt("Dim");var palette=before.getList("DimensionPalette",10);if(dim<0||dim>=palette.size()||!palette.getCompound(dim).getString("Id").equals(body.before().getString("Dimension")))throw new IllegalStateException("Измерение вагона не совпадает с реестром");
        }
        if(seen.size()!=carriages.size())throw new IllegalStateException("Не все вагоны сохранены");
        for(String field:List.of("Graph","Station","Signals"))if(before.contains(field)!=after.contains(field))throw new IllegalStateException("Неполное окружение поезда");
        if(before.contains("Graph",10)&&(!before.getCompound("Graph").equals(after.getCompound("Graph"))||!nativeTag.hasUUID("Graph")||!nativeTag.getUUID("Graph").equals(before.getCompound("Graph").getCompound("Native").getUUID("Id"))))throw new IllegalStateException("Путевой граф изменён при разборке");
        if(nativeTag.hasUUID("Graph")&&!before.contains("Graph",10))throw new IllegalStateException("Нет снимка путевого графа");
        if(before.contains("Station",10)){var station=before.getCompound("Station");var next=after.getCompound("Station");if(!station.hasUUID("Id")||!station.getUUID("Id").equals(nativeTag.getUUID("Station"))||!station.getUUID("Id").equals(next.getUUID("Id"))||!station.getString("Dimension").equals(next.getString("Dimension"))||station.getLong("Pos")!=next.getLong("Pos")||world.stream().noneMatch(e->e.before().getLong("Pos")==station.getLong("Pos")&&e.before().getString("Dimension").equals(station.getString("Dimension"))))throw new IllegalStateException("Станция не сохранена вместе с миром");}
    }
    private static void compoundList(CompoundTag tag,String key){if(!(tag.get(key) instanceof ListTag list)||!list.isEmpty()&&list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalStateException("Повреждён список поезда: "+key);}
    /** Pure NBT preparation on the existing rollback worker. */
    public static final class Prepared {
        final CompoundTag shell;final List<CompoundTag> carriages=new ArrayList<>();final boolean serialized;
        public Prepared(CompoundTag before){this(before,false);}
        public Prepared(CompoundTag before,boolean serialized){this.serialized=serialized;shell=before.getCompound("Native").copy();shell.remove("Station");
            for(var raw:shell.getList("Carriages",10)){var carriage=((CompoundTag)raw).copy();
                if(!serialized){carriage.put("Entity",new CompoundTag());for(var pos:carriage.getList("EntityPositioning",10)){var storage=(CompoundTag)pos;storage.remove("items");storage.remove("fluids");}}
                carriages.add(carriage);}shell.put("Carriages",new ListTag());}

    }
    /** Restore this train only. The saved graph is a conflict witness, never a rail-world undo. */
    public static final class Endpoint {
        final MinecraftServer server;final CompoundTag before,after;final UUID id;final TrackGraph graph;long epoch;public Train restored;private final CompoundTag shell;private final List<CompoundTag> carriages;private final DimensionPalette palette;private final boolean serialized;private int index;private boolean started,installed;
        private final Map<UUID,List<Train>> staleSignalTrains=new HashMap<>();private final Map<UUID,com.simibubi.create.content.trains.signal.SignalBoundary> staleSignalReservations=new HashMap<>();
        public Endpoint(MinecraftServer server,CompoundTag before,CompoundTag after,Prepared prepared){this.server=server;this.before=before;this.after=after;id=before.getUUID("Id");palette=DimensionPalette.read(before);graph=before.getCompound("Native").hasUUID("Graph")?Create.RAILWAYS.trackNetworks.get(before.getCompound("Native").getUUID("Graph")):null;
            verifyEnvironment();for(var raw:before.getList("Signals",10)){var saved=(CompoundTag)raw;var group=Create.RAILWAYS.signalEdgeGroups.get(saved.getUUID("Id"));staleSignalTrains.put(group.id,group.trains.stream().filter(t->t.id.equals(id)).toList());if(saved.hasUUID("Reserved")&&group.reserved!=null&&group.reserved.id.equals(saved.getUUID("Reserved")))staleSignalReservations.put(group.id,group.reserved);}if(Create.RAILWAYS.trains.containsKey(id)||((CreateRailwayAccess)Create.RAILWAYS).avilixlogger$moving(id)>=0||((CreateRailwayAccess)Create.RAILWAYS).avilixlogger$waiting(id)>=0)throw new IllegalStateException("Поезд уже присутствует в RailwayManager");
            CartRestoreLocks.resource(key(id));if(before.contains("Station",10))CartRestoreLocks.resource("create/station/"+before.getCompound("Station").getUUID("Id"));
            epoch=graph instanceof CreateTrackEpoch access?access.avilixlogger$epoch():0;shell=prepared.shell;carriages=prepared.carriages;serialized=prepared.serialized;
        }
        public boolean stage(){
            if(!started){restored=Train.read(shell,server.registryAccess(),Create.RAILWAYS.trackNetworks,palette);if(before.getCompound("Native").hasUUID("Station"))restored.currentStation=before.getCompound("Native").getUUID("Station");((CreateTrainRuntimeAccess)restored).avilixlogger$trainRuntime(before.getCompound("Runtime"),server);((CreateNavigationAccess)restored.navigation).avilixlogger$navigation(before.getCompound("NavigationRuntime"),graph,palette);started=true;return false;}
            if(index<carriages.size()){var carriage=Carriage.read(carriages.get(index),server.registryAccess(),graph,palette);carriage.blocked=before.getList("CarriageRuntime",10).getCompound(index).getBoolean("Blocked");var extras=before.getList("CarriageRuntime",10).getCompound(index).getList("Points",10);int p=0;for(var bogey:carriage.bogeys)if(bogey!=null)for(var point:List.of(bogey.leading(),bogey.trailing())){var value=extras.getCompound(p++);point.blocked=value.getBoolean("Blocked");point.position=value.getDouble("Position");point.upsideDown=value.getBoolean("UpsideDown");}carriage.setTrain(restored);restored.carriages.add(carriage);
                if(serialized){var positions=carriages.get(index).getList("EntityPositioning",10);((CreateTrainCargoAccess)carriage.storage).avilixlogger$cargoRuntime(positions.getCompound(positions.size()-1).getInt("TicksSinceLastExchange"),before.getList("CarriageRuntime",10).getCompound(index).getInt("CargoVersion"));}
                index++;return false;}return true;
        }
        public void bind(StagedContraptionRestore body,CompoundTag entity){if(!entity.getString("id").equals("create:carriage_contraption"))return;int i=entity.getInt("CarriageIndex");if(!id.equals(entity.getUUID("TrainId"))||i<0||i>=restored.carriages.size())throw new IllegalStateException("Вагон не принадлежит подготовленному поезду");body.carriage(restored.carriages.get(i));}
        public void bodyReady(CarriageContraptionEntity entity){var carriage=entity.getCarriage();carriage.getDimensional(entity.level()).entity=new WeakReference<>(entity);var state=before.getList("CarriageRuntime",10).getCompound(entity.carriageIndex);var nativeData=before.getCompound("Native").getList("Carriages",10).getCompound(entity.carriageIndex).getList("EntityPositioning",10).getCompound(0);((CreateTrainCargoAccess)carriage.storage).avilixlogger$cargoRuntime(nativeData.getInt("TicksSinceLastExchange"),state.getInt("CargoVersion"));carriage.getDimensional(entity.level()).updateRenderedCutoff();}
        public void ready(){verifyEnvironment();verifyPayload();epoch=graph instanceof CreateTrackEpoch access?access.avilixlogger$epoch():0;}
        public void freeze(){ready();CartRestoreLocks.resource(RAILWAY_MUTATION);}
        public void unchanged(){if(graph!=null&&(Create.RAILWAYS.trackNetworks.get(graph.id)!=graph||((CreateTrackEpoch)graph).avilixlogger$epoch()!=epoch))throw new IllegalStateException("Путевой граф изменён во время отката");}
        public void install(){unchanged();if(Create.RAILWAYS.trains.containsKey(id))throw new IllegalStateException("ID поезда уже занят");installed=true;((CreateRailwayAccess)Create.RAILWAYS).avilixlogger$install(restored,before.getInt("Moving"),before.getInt("Waiting"));if(before.contains("Station",10)){var station=restored.getCurrentStation();var tag=before.getCompound("Station");station.nearestTrain=new WeakReference<>(tag.hasUUID("Nearest")?Create.RAILWAYS.trains.get(tag.getUUID("Nearest")):null);}signals(true);}
        public void verify(){unchanged();verifyEnvironment();if(Create.RAILWAYS.trains.get(id)!=restored||(((CreateRailwayAccess)Create.RAILWAYS).avilixlogger$moving(id)>=0)!=(before.getInt("Moving")>=0)||(((CreateRailwayAccess)Create.RAILWAYS).avilixlogger$waiting(id)>=0)!=(before.getInt("Waiting")>=0))throw new IllegalStateException("Поезд не зарегистрирован в исходной очереди");
            for(var raw:before.getList("Signals",10)){var saved=(CompoundTag)raw;var group=Create.RAILWAYS.signalEdgeGroups.get(saved.getUUID("Id"));if(group.trains.contains(restored)!=saved.getBoolean("Present")||saved.hasUUID("Reserved")&&(group.reserved==null||!group.reserved.id.equals(saved.getUUID("Reserved"))))throw new IllegalStateException("Резерв/занятость сигнала не восстановлены");}
            verifyPayload();
        }
        private void verifyPayload(){
            if(!carriageRuntime(restored).equals(before.getList("CarriageRuntime",10)))throw new IllegalStateException("Runtime вагона или груза отличается от снимка");
            var actual=write(restored,new DimensionPalette(new ArrayList<>(before.getList("DimensionPalette",10).stream().map(raw->net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.ResourceLocation.parse(((CompoundTag)raw).getString("Id")))).toList())),server.registryAccess());var expected=before.getCompound("Native").copy();orderRepresentations(expected);if(!actual.equals(expected))throw new IllegalStateException("NBT восстановленного поезда отличается от снимка: "+difference(actual,expected,"Native"));
            if(!((CreateNavigationAccess)restored.navigation).avilixlogger$navigation(palette).equals(before.getCompound("NavigationRuntime")))throw new IllegalStateException("Навигация поезда отличается от снимка");
            if(!((CreateTrainRuntimeAccess)restored).avilixlogger$trainRuntime(server.registryAccess()).equals(before.getCompound("Runtime")))throw new IllegalStateException("Runtime поезда отличается от снимка");
        }
        public void compensate(){if(!installed)return;((CreateRailwayAccess)Create.RAILWAYS).avilixlogger$remove(id);signals(false);if(before.contains("Station",10)){var station=graph==null?null:graph.getPoint(EdgePointType.STATION,before.getCompound("Station").getUUID("Id"));if(station!=null&&station.nearestTrain.get()==restored){var saved=after.getCompound("Station");station.nearestTrain=new WeakReference<>(saved.hasUUID("Nearest")?Create.RAILWAYS.trains.get(saved.getUUID("Nearest")):null);}}installed=false;}
        public void publish(){net.createmod.catnip.platform.CatnipServices.NETWORK.sendToAllClients(new AddTrainPacket(restored));Create.RAILWAYS.markTracksDirty();}
        private void verifyEnvironment(){
            if(before.contains("Graph",10)){if(graph==null||Create.RAILWAYS.trackNetworks.get(graph.id)!=graph)throw new IllegalStateException("Путевой граф отсутствует или заменён");var expected=before.getCompound("Graph");var dimensions=DimensionPalette.read(expected);var actual=new CompoundTag();actual.put("Native",graph.write(server.registryAccess(),dimensions));dimensions.write(actual);if(!actual.equals(expected))throw new IllegalStateException("Путевой граф изменён после разборки");}
            if(before.contains("Station",10)){var saved=(installed?before:after).getCompound("Station");var station=graph==null?null:graph.getPoint(EdgePointType.STATION,saved.getUUID("Id"));if(station==null)throw new IllegalStateException("Станция отсутствует");var actual=saved.copy();nearest(actual,station);if(!actual.equals(saved))throw new IllegalStateException("Резерв станции изменён");}
            for(var raw:before.getList("Signals",10)){var saved=(CompoundTag)raw;var group=Create.RAILWAYS.signalEdgeGroups.get(saved.getUUID("Id"));if(group==null)throw new IllegalStateException("Сигнальная группа отсутствует");var tag=group.write();if(!tag.equals(saved.getCompound("Native"))||group.trains.stream().anyMatch(t->!t.id.equals(id))||Create.RAILWAYS.trains.values().stream().anyMatch(t->!t.id.equals(id)&&t.reservedSignalBlocks.contains(group.id))||group.reserved!=null&&(!saved.hasUUID("Reserved")||!group.reserved.id.equals(saved.getUUID("Reserved"))))throw new IllegalStateException("Сигнальный блок изменён или занят другим поездом");}
        }
        private void signals(boolean restore){for(var raw:before.getList("Signals",10)){var saved=(CompoundTag)raw;var group=Create.RAILWAYS.signalEdgeGroups.get(saved.getUUID("Id"));if(group==null){if(restore)throw new IllegalStateException("Сигнальный блок отсутствует");continue;}group.trains.removeIf(t->t.id.equals(id));if(restore&&saved.getBoolean("Present"))group.trains.add(restored);if(!restore)group.trains.addAll(staleSignalTrains.getOrDefault(group.id,List.of()));if(saved.hasUUID("Reserved")){var boundary=graph==null?null:graph.getPoint(EdgePointType.SIGNAL,saved.getUUID("Reserved"));if(restore){if(boundary==null)throw new IllegalStateException("Граница резерва отсутствует");group.reserved=boundary;}else if(group.reserved==boundary)group.reserved=staleSignalReservations.get(group.id);}}}
    }
}

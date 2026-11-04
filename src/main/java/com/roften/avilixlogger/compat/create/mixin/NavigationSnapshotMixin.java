package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.*;
import com.simibubi.create.content.trains.entity.*;
import com.simibubi.create.content.trains.graph.*;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import net.createmod.catnip.data.Couple;
import net.minecraft.nbt.*;
import net.createmod.catnip.data.Pair;
import org.spongepowered.asm.mixin.*;
import java.util.*;
@Mixin(value=Navigation.class,remap=false)
public abstract class NavigationSnapshotMixin implements CreateNavigationAccess {
    @org.spongepowered.asm.mixin.injection.Inject(method={"cancelNavigation","tick"},at=@org.spongepowered.asm.mixin.injection.At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$locked(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){if(CreateTrainRegistry.locked(((Navigation)(Object)this).train.id))ci.cancel();}
    @org.spongepowered.asm.mixin.injection.Inject(method="startNavigation",at=@org.spongepowered.asm.mixin.injection.At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$start(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Double> ci){if(CreateTrainRegistry.locked(((Navigation)(Object)this).train.id))ci.setReturnValue(-1d);}
    @Shadow private TravellingPoint signalScout;
    @Shadow private Map<UUID,Pair<SignalBoundary,Boolean>> waitingForChainedGroups;
    @Shadow List<Couple<TrackNode>> currentPath;
    public CompoundTag avilixlogger$navigation(DimensionPalette palette){
        var self=(Navigation)(Object)this;var tag=new CompoundTag();tag.put("Scout",signalScout.write(palette));tag.putDouble("ScoutPosition",signalScout.position);tag.putBoolean("ScoutBlocked",signalScout.blocked);tag.putBoolean("ScoutUpsideDown",signalScout.upsideDown);
        if(self.waitingForSignal!=null){tag.putUUID("WaitingSignal",self.waitingForSignal.getFirst());tag.putBoolean("WaitingSide",self.waitingForSignal.getSecond());}tag.putDouble("SignalDistance",self.distanceToSignal);tag.putInt("WaitingTicks",self.ticksWaitingForSignal);tag.putDouble("DestinationDistance",self.distanceToDestination);tag.putDouble("StartedDistance",self.distanceStartedAt);tag.putBoolean("Behind",self.destinationBehindTrain);tag.putBoolean("Announce",self.announceArrival);
        var chained=new ListTag();waitingForChainedGroups.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{var value=new CompoundTag();value.putUUID("Group",e.getKey());value.putUUID("Boundary",e.getValue().getFirst().id);value.putBoolean("Side",e.getValue().getSecond());chained.add(value);});tag.put("Chained",chained);
        var path=new ListTag();for(var pair:currentPath){if(pair.either(Objects::isNull))throw new IllegalStateException("Неполный путь навигации");var entry=new CompoundTag();var nodes=new ListTag();nodes.add(pair.getFirst().getLocation().write(palette));nodes.add(pair.getSecond().getLocation().write(palette));entry.put("Nodes",nodes);path.add(entry);}tag.put("Path",path);return tag;
    }
    public void avilixlogger$navigation(CompoundTag tag,TrackGraph graph,DimensionPalette palette){
        var self=(Navigation)(Object)this;signalScout=TravellingPoint.read(tag.getCompound("Scout"),graph,palette);signalScout.position=tag.getDouble("ScoutPosition");signalScout.blocked=tag.getBoolean("ScoutBlocked");signalScout.upsideDown=tag.getBoolean("ScoutUpsideDown");
        self.waitingForSignal=tag.hasUUID("WaitingSignal")?Pair.of(tag.getUUID("WaitingSignal"),tag.getBoolean("WaitingSide")):null;self.distanceToSignal=tag.getDouble("SignalDistance");self.ticksWaitingForSignal=tag.getInt("WaitingTicks");self.distanceToDestination=tag.getDouble("DestinationDistance");self.distanceStartedAt=tag.getDouble("StartedDistance");self.destinationBehindTrain=tag.getBoolean("Behind");self.announceArrival=tag.getBoolean("Announce");
        waitingForChainedGroups.clear();for(var raw:tag.getList("Chained",10)){var value=(CompoundTag)raw;var boundary=graph==null?null:graph.getPoint(EdgePointType.SIGNAL,value.getUUID("Boundary"));if(boundary==null)throw new IllegalStateException("Граница цепного сигнала отсутствует");waitingForChainedGroups.put(value.getUUID("Group"),Pair.of(boundary,value.getBoolean("Side")));}
        currentPath.clear();for(var raw:tag.getList("Path",10)){var nodes=((CompoundTag)raw).getList("Nodes",10);if(graph==null||nodes.size()!=2)throw new IllegalStateException("Граф пути отсутствует");var first=graph.locateNode(TrackNodeLocation.read(nodes.getCompound(0),palette));var second=graph.locateNode(TrackNodeLocation.read(nodes.getCompound(1),palette));if(first==null||second==null||graph.getConnectionsFrom(first)==null||!graph.getConnectionsFrom(first).containsKey(second))throw new IllegalStateException("Ребро пути отсутствует");currentPath.add(Couple.create(first,second));}
    }
}

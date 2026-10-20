package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateTrainRuntimeAccess;
import com.simibubi.create.content.trains.entity.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.*;
import java.util.*;
@Mixin(value=Train.class,remap=false)
public abstract class TrainRuntimeMixin implements CreateTrainRuntimeAccess {
    @Shadow int tickOffset;
    @Shadow int ticksSinceLastMailTransfer;
    @Shadow double[] stress;
    public CompoundTag avilixlogger$trainRuntime(HolderLookup.Provider registries){
        var self=(Train)(Object)this;var tag=new CompoundTag();tag.putInt("TickOffset",tickOffset);tag.putInt("MailTicks",ticksSinceLastMailTransfer);var values=new ListTag();for(double value:stress)values.add(DoubleTag.valueOf(value));tag.put("CouplingStress",values);
        tag.putBoolean("Invalid",self.invalid);tag.putInt("MigrationCooldown",self.migrationCooldown);tag.putInt("WaitingForChunks",self.carriageWaitingForChunks);tag.putBoolean("ManualTick",self.manualTick);tag.putString("ManualSteer",self.manualSteer.name());tag.putBoolean("Honk",self.honk);tag.putInt("HonkTicks",self.honkTicks);tag.putInt("HonkPitch",self.honkPitch);tag.putFloat("SteamRelease",self.accumulatedSteamRelease);if(self.lowHonk!=null)tag.putBoolean("LowHonk",self.lowHonk);if(self.backwardsDriver!=null)tag.putUUID("BackwardsDriver",self.backwardsDriver.getUUID());
        var cached=new ListTag();self.cachedObserverFiltering.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{var value=new CompoundTag();value.putUUID("Id",e.getKey());value.putInt("Index",e.getValue().getFirst());value.putBoolean("Match",e.getValue().getSecond());cached.add(value);});tag.put("ObserverCache",cached);
        var status=new CompoundTag();status.putBoolean("Navigation",self.status.navigation);status.putBoolean("Track",self.status.track);status.putBoolean("Conductor",self.status.conductor);var queued=new ListTag();
        for(var message:((TrainStatusAccessor)self.status).avilixlogger$queued()){var components=new ListTag();for(var component:message.messages())components.add(StringTag.valueOf(Component.Serializer.toJson(component,registries)));queued.add(components);}status.put("Queued",queued);tag.put("Status",status);var schedule=new CompoundTag();schedule.putInt("Cooldown",((ScheduleRuntimeAccessor)self.runtime).avilixlogger$cooldown());schedule.putInt("TransitTicks",self.runtime.ticksInTransit);if(self.runtime.currentTitle!=null)schedule.putString("Title",self.runtime.currentTitle);schedule.putBoolean("DisplayUpdate",self.runtime.displayLinkUpdateRequested);tag.put("ScheduleRuntime",schedule);return tag;
    }
    public void avilixlogger$trainRuntime(CompoundTag tag,MinecraftServer server){
        var self=(Train)(Object)this;tickOffset=tag.getInt("TickOffset");ticksSinceLastMailTransfer=tag.getInt("MailTicks");var values=tag.getList("CouplingStress",6);stress=new double[values.size()];for(int i=0;i<stress.length;i++)stress[i]=values.getDouble(i);
        self.invalid=tag.getBoolean("Invalid");self.migrationCooldown=tag.getInt("MigrationCooldown");self.carriageWaitingForChunks=tag.getInt("WaitingForChunks");self.manualTick=tag.getBoolean("ManualTick");self.manualSteer=TravellingPoint.SteerDirection.valueOf(tag.getString("ManualSteer"));self.honk=tag.getBoolean("Honk");self.honkTicks=tag.getInt("HonkTicks");self.honkPitch=tag.getInt("HonkPitch");self.accumulatedSteamRelease=tag.getFloat("SteamRelease");self.lowHonk=tag.contains("LowHonk")?tag.getBoolean("LowHonk"):null;
        self.backwardsDriver=tag.hasUUID("BackwardsDriver")?server.getPlayerList().getPlayer(tag.getUUID("BackwardsDriver")):null;if(tag.hasUUID("BackwardsDriver")&&self.backwardsDriver==null)throw new IllegalStateException("Водитель поезда отсутствует");
        self.cachedObserverFiltering.clear();for(var raw:tag.getList("ObserverCache",10)){var value=(CompoundTag)raw;self.cachedObserverFiltering.put(value.getUUID("Id"),net.createmod.catnip.data.Pair.of(value.getInt("Index"),value.getBoolean("Match")));}
        var status=tag.getCompound("Status");var schedule=tag.getCompound("ScheduleRuntime");((ScheduleRuntimeAccessor)self.runtime).avilixlogger$cooldown(schedule.getInt("Cooldown"));self.runtime.ticksInTransit=schedule.getInt("TransitTicks");self.runtime.currentTitle=schedule.contains("Title",8)?schedule.getString("Title"):null;self.runtime.displayLinkUpdateRequested=schedule.getBoolean("DisplayUpdate");self.status.navigation=status.getBoolean("Navigation");self.status.track=status.getBoolean("Track");self.status.conductor=status.getBoolean("Conductor");var queue=((TrainStatusAccessor)self.status).avilixlogger$queued();queue.clear();for(var raw:status.getList("Queued",9)){var list=(ListTag)raw;var components=new Component[list.size()];for(int i=0;i<components.length;i++)components[i]=Objects.requireNonNull(Component.Serializer.fromJson(list.getString(i),server.registryAccess()));queue.add(new TrainStatus.StatusMessage(components));}
    }
}

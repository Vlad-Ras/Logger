package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateKineticNetworkAccess;
import com.roften.avilixlogger.core.CartRestoreLocks;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;
@Mixin(value=KineticNetwork.class,remap=false)
public abstract class KineticNetworkStateMixin implements CreateKineticNetworkAccess {
    @Shadow public Long id;
    @Shadow public boolean initialized;
    @Shadow public Map<KineticBlockEntity,Float> sources;
    @Shadow public Map<KineticBlockEntity,Float> members;
    @Shadow private float currentCapacity;
    @Shadow private float currentStress;
    @Shadow private float unloadedCapacity;
    @Shadow private float unloadedStress;
    @Shadow private int unloadedMembers;
    public CompoundTag avilixlogger$network(){var tag=new CompoundTag();tag.putBoolean("Initialized",initialized);tag.putFloat("Capacity",currentCapacity);tag.putFloat("Stress",currentStress);tag.putFloat("UnloadedCapacity",unloadedCapacity);tag.putFloat("UnloadedStress",unloadedStress);tag.putInt("UnloadedMembers",unloadedMembers);tag.put("Members",avilixlogger$list(members));tag.put("Sources",avilixlogger$list(sources));return tag;}
    @Unique private static ListTag avilixlogger$list(Map<KineticBlockEntity,Float> map){var result=new ListTag();map.entrySet().stream().sorted(Comparator.comparingLong(e->e.getKey().getBlockPos().asLong())).forEach(e->{var value=new CompoundTag();value.putLong("Pos",e.getKey().getBlockPos().asLong());value.putFloat("Value",e.getValue());result.add(value);});return result;}
    public void avilixlogger$network(ServerLevel level,CompoundTag tag){initialized=tag.getBoolean("Initialized");currentCapacity=tag.getFloat("Capacity");currentStress=tag.getFloat("Stress");unloadedCapacity=tag.getFloat("UnloadedCapacity");unloadedStress=tag.getFloat("UnloadedStress");unloadedMembers=tag.getInt("UnloadedMembers");members=avilixlogger$map(level,tag.getList("Members",10));sources=avilixlogger$map(level,tag.getList("Sources",10));}
    @Unique private static Map<KineticBlockEntity,Float> avilixlogger$map(ServerLevel level,ListTag list){var result=new HashMap<KineticBlockEntity,Float>();for(var raw:list){var tag=(CompoundTag)raw;var pos=BlockPos.of(tag.getLong("Pos"));if(!level.hasChunkAt(pos)||!(level.getBlockEntity(pos) instanceof KineticBlockEntity be))throw new IllegalStateException("Участник кинетической сети отсутствует");result.put(be,tag.getFloat("Value"));}return result;}
    @Inject(method={"initFromTE","addSilently","add","updateCapacityFor","updateStressFor","remove","sync","updateCapacity","updateStress"},at=@At("HEAD"),cancellable=true)
    private void avilixlogger$locked(CallbackInfo ci){if(CartRestoreLocks.handlerLocked(this))ci.cancel();}
}

package com.roften.avilixlogger.compat.create;
import com.roften.avilixlogger.compat.create.mixin.TorqueNetworksAccessor;
import com.roften.avilixlogger.core.CartRestoreLocks;
import com.simibubi.create.content.kinetics.KineticNetwork;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import java.util.*;
public final class CreateKineticRegistry {
    private CreateKineticRegistry(){}
    public static String key(ServerLevel level,long id){return level.dimension().location()+"/kinetic/"+id;}
    public static Map<Long,KineticNetwork> networks(ServerLevel level){return TorqueNetworksAccessor.avilixlogger$networks().computeIfAbsent(level,k->new HashMap<>());}
    public static CompoundTag snapshot(ServerLevel level,long id){var tag=new CompoundTag();tag.putLong("Id",id);var network=networks(level).get(id);tag.putBoolean("Exists",network!=null);if(network!=null)tag.put("State",((CreateKineticNetworkAccess)network).avilixlogger$network());return tag;}
    public static long validate(CompoundTag tag){
        if(!tag.contains("Id",4)||!tag.contains("Exists",1))throw new IllegalStateException("Нет адреса кинетического реестра");
        if(tag.getBoolean("Exists")){var state=tag.getCompound("State");for(String field:List.of("Capacity","Stress","UnloadedCapacity","UnloadedStress"))if(!state.contains(field,5)||!Float.isFinite(state.getFloat(field)))throw new IllegalStateException("Повреждены значения кинетического реестра");
            if(!state.contains("Initialized",1)||!state.contains("UnloadedMembers",3)||state.getInt("UnloadedMembers")<0)throw new IllegalStateException("Нет полного состояния кинетической сети");
            var members=new HashSet<Long>();for(String field:List.of("Members","Sources")){var seen=new HashSet<Long>();var list=state.getList(field,10);if(!state.contains(field,9)||list.size()>8192)throw new IllegalStateException("Неполный состав кинетической сети");
                for(var raw:list){var value=(CompoundTag)raw;if(!value.contains("Pos",4)||!value.contains("Value",5)||!Float.isFinite(value.getFloat("Value"))||!seen.add(value.getLong("Pos")))throw new IllegalStateException("Повреждён участник кинетической сети");if(field.equals("Sources")&&!members.contains(value.getLong("Pos")))throw new IllegalStateException("Источник отсутствует среди участников");}if(field.equals("Members"))members.addAll(seen);}
        }else if(tag.contains("State"))throw new IllegalStateException("Отсутствующая сеть содержит состояние");return tag.getLong("Id");
    }
    public static void reserve(ServerLevel level,CompoundTag tag){long id=validate(tag);CartRestoreLocks.resource(key(level,id));var network=networks(level).get(id);if(network!=null)CartRestoreLocks.handler(network);}
    public static void restore(ServerLevel level,CompoundTag tag){long id=validate(tag);var networks=networks(level);if(!tag.getBoolean("Exists")){networks.remove(id);return;}var network=networks.computeIfAbsent(id,k->{var result=new KineticNetwork();result.id=k;return result;});CartRestoreLocks.handler(network);((CreateKineticNetworkAccess)network).avilixlogger$network(level,tag.getCompound("State"));if(!snapshot(level,id).equals(tag))throw new IllegalStateException("Кинетический реестр не восстановлен полностью");}
}

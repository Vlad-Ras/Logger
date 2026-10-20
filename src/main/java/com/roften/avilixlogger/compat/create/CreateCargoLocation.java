package com.roften.avilixlogger.compat.create;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import java.util.*;
/** Train cargo belongs to a persistent carriage, not to one temporary portal entity. */
final class CreateCargoLocation {
    private final CompoundTag address;private final Train train;private final Carriage carriage;private final Object storage;
    final AbstractContraptionEntity entity;
    CreateCargoLocation(MinecraftServer server,CompoundTag address){
        this.address=address.copy();
        if(address.getString("ConstructionKind").equals("TRAIN")){
            if(address.getBoolean("BaseCart")||!address.hasUUID("Carriage"))throw new IllegalStateException("Нет постоянного адреса вагона");
            train=Create.RAILWAYS.trains.get(address.getUUID("Construction"));if(train==null)throw new IllegalStateException("Поезд отсутствует в RailwayManager");
            var found=train.carriages.stream().filter(c->address.getUUID("Carriage").equals(((ContraptionIdentity)c).avilixlogger$identity())).toList();
            if(found.size()!=1)throw new IllegalStateException("Вагон отсутствует или его ID неоднозначен");
            carriage=found.getFirst();storage=carriage.storage;entity=carriage.anyAvailableEntity();
        }else{
            train=null;carriage=null;var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(address.getString("Dimension"))));
            if(level==null||!(level.getEntity(address.getUUID("StorageEntity")) instanceof AbstractContraptionEntity body)||body.isRemoved()||body.getContraption()==null)
                throw new IllegalStateException("Конструкция выгружена или удалена");entity=body;storage=body.getContraption().getStorage();
        }
        verify();
    }
    void verify(){
        if(train!=null){
            if(Create.RAILWAYS.trains.get(train.id)!=train||carriage.train!=train||carriage.storage!=storage
                    ||train.carriages.stream().filter(c->address.getUUID("Carriage").equals(((ContraptionIdentity)c).avilixlogger$identity())).count()!=1
                    ||!train.carriages.contains(carriage))throw new IllegalStateException("Поезд, вагон или его хранилище заменены");
        }else if(entity.isRemoved()||entity.getContraption()==null||entity.getContraption().getStorage()!=storage
                ||!CreateContraptionSources.id(entity).equals(address.getUUID("Construction"))||!CreateContraptionSources.kind(entity).name().equals(address.getString("ConstructionKind")))
            throw new IllegalStateException("Конструкция или её хранилище заменены");
    }
    Object resolve(BlockPos local,boolean item,boolean base){
        verify();if(train!=null)return item?carriage.storage.getAllItemStorages().get(local):carriage.storage.getFluids().storages.get(local);
        if(base)return entity.getVehicle() instanceof net.minecraft.world.Container container?new net.neoforged.neoforge.items.wrapper.InvWrapper(container):null;
        return item?entity.getContraption().getStorage().getAllItemStorages().get(local):entity.getContraption().getStorage().getFluids().storages.get(local);
    }
    void reserve(MinecraftServer server){
        CartRestoreLocks.construction(address.getUUID("Construction"));CartStorageAudit.lockMenus(address.getUUID("Construction"),server);
        if(train!=null){CartRestoreLocks.resource(CreateTrainRegistry.key(train.id));
            for(var car:train.carriages)car.forEachPresentEntity(body->{if(!body.isRemoved())reserveBody(body);});
        }else reserveBody(entity);
    }
    private static void reserveBody(AbstractContraptionEntity body){
        CartAuditContext.LOCKED_ENTITIES.add(body.getUUID());CartRestoreLocks.keepLoaded((net.minecraft.server.level.ServerLevel)body.level(),body.blockPosition());
        if(body.getVehicle()!=null){CartAuditContext.LOCKED_ENTITIES.add(body.getVehicle().getUUID());if(body.getVehicle() instanceof net.minecraft.world.Container container)CartRestoreLocks.container(container);}
    }
}
